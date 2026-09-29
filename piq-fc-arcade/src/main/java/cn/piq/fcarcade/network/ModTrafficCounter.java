package cn.piq.fcarcade.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded, connection-local, thread-safe statistics; not a recorder of network contents. */
public final class ModTrafficCounter implements ModTrafficProbe.Collector {
    public static final int MAX_STREAMS = 16;
    public record StreamRate(UUID id,double fps) {}
    public record CategoryRate(double up,double down,long uploaded,long downloaded) {
        public long totalBytes(){return saturate(uploaded,downloaded);}
    }
    public record Sample(double uploadBytesPerSecond,double downloadBytesPerSecond,List<StreamRate> streams,
                         long uploadedBytes,long downloadedBytes,Map<TrafficCategory,CategoryRate> categories) {
        public Sample { streams=List.copyOf(streams);categories=Map.copyOf(categories); }
        public Sample(double up,double down,List<StreamRate> streams,long uploaded,long downloaded) { this(up,down,streams,uploaded,downloaded,Map.of()); }
        public Sample(double up,double down,List<StreamRate> streams) { this(up,down,streams,0,0); }
        public long totalBytes() { return saturate(uploadedBytes,downloadedBytes); }
    }
    private static final class Frames { long count,last; Frames(long now) { last=now; } }
    private final LongSupplier clock;
    private long began,upload,download,totalUpload,totalDownload;
    private final Map<UUID,Frames> frames = new LinkedHashMap<>();
    private final long[][] buckets=new long[TrafficCategory.values().length][4];
    private Sample last = new Sample(0,0,List.of());
    public ModTrafficCounter() { this(System::nanoTime); }
    public ModTrafficCounter(LongSupplier clock) { this.clock=clock; began=clock.getAsLong(); }
    @Override public void upload(int bytes) { upload(TrafficCategory.OTHER,bytes); }
    @Override public void download(int bytes) { download(TrafficCategory.OTHER,bytes); }
    @Override public synchronized void upload(TrafficCategory category,int bytes) {
        if(bytes<=0)return;var b=buckets[category.ordinal()];
        upload=saturate(upload,bytes);totalUpload=saturate(totalUpload,bytes);b[0]=saturate(b[0],bytes);b[2]=saturate(b[2],bytes);
    }
    @Override public synchronized void download(TrafficCategory category,int bytes) {
        if(bytes<=0)return;var b=buckets[category.ordinal()];
        download=saturate(download,bytes);totalDownload=saturate(totalDownload,bytes);b[1]=saturate(b[1],bytes);b[3]=saturate(b[3],bytes);
    }
    /** Explicit local measurement reset; does not reconnect or affect any emulator/session. */
    public synchronized void reset(){
        began=clock.getAsLong();upload=download=totalUpload=totalDownload=0;frames.clear();
        for(var b:buckets)java.util.Arrays.fill(b,0);last=new Sample(0,0,List.of());
    }
    @Override public synchronized void video(UUID stream) {
        if(stream==null)return;
        long now=clock.getAsLong(); prune(now);
        var f=frames.get(stream);
        if(f==null) { if(frames.size()>=MAX_STREAMS)return; f=new Frames(now);frames.put(stream,f); }
        f.count=saturate(f.count,1);f.last=now;
    }
    public synchronized Sample sample() {
        long now=clock.getAsLong(),elapsed=now-began;
        if(elapsed<0) { began=now;upload=download=0;frames.clear();for(var b:buckets)b[0]=b[1]=0;
            return last=new Sample(0,0,List.of(),totalUpload,totalDownload,categoryRates(1)); }
        if(elapsed<1_000_000_000L)return new Sample(last.uploadBytesPerSecond(),last.downloadBytesPerSecond(),last.streams(),totalUpload,totalDownload,categoryRates(0));
        prune(now);double seconds=elapsed/1_000_000_000.0;
        var rates=frames.entrySet().stream().map(e->new StreamRate(e.getKey(),e.getValue().count/seconds)).toList();
        last=new Sample(upload/seconds,download/seconds,rates,totalUpload,totalDownload,categoryRates(seconds));
        upload=download=0;for(var b:buckets)b[0]=b[1]=0;for(var f:frames.values())f.count=0;began=now;return last;
    }
    private Map<TrafficCategory,CategoryRate> categoryRates(double seconds){
        var result=new EnumMap<TrafficCategory,CategoryRate>(TrafficCategory.class);
        for(var c:TrafficCategory.values()){
            var b=buckets[c.ordinal()];var prior=last.categories().get(c);
            result.put(c,new CategoryRate(seconds>0?b[0]/seconds:prior==null?0:prior.up(),
                    seconds>0?b[1]/seconds:prior==null?0:prior.down(),b[2],b[3]));
        }
        return result;
    }
    private void prune(long now) { frames.values().removeIf(f->now-f.last>2_000_000_000L); }
    private static long saturate(long total,long bytes) { return total>Long.MAX_VALUE-bytes?Long.MAX_VALUE:total+bytes; }
}
