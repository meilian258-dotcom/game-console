package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.*;
import cn.piq.replaycompat.io.TrackCodec;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/** Detached decode, byte-exact v1 compatibility, and optional read-only real-distribution timings. */
public final class ScreenDecodePipelineTest {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    public static void main(String[] args) throws Exception {
        bulkCompatibility(); entryBounds(); detachedPlayback(); closedBeforeRead();
        if (args.length > 0) realArchive(Path.of(args[0]));
        System.out.println("ScreenDecodePipelineTest: " + CHECKS.get() + " checks passed");
    }
    private static void bulkCompatibility() throws Exception {
        Random random = new Random(41829);
        for (int[] size : new int[][]{{1,1},{31,17},{64,64},{65,64},{256,239},{640,480},{1024,1024}}) {
            int[] pixels = new int[size[0] * size[1]]; for (int n=0;n<pixels.length;n++) pixels[n]=random.nextInt();
            var frames = List.of(frame(0,size[0],size[1],pixels), ScreenFrame.closed("screen","minecraft:overworld",50));
            byte[] old = legacyWrite(frames), current = encode(frames);
            check(Arrays.equals(old,current), "bulk encoding is byte-identical to v1 scalar encoder");
            same(frames,TrackCodec.read(new ByteArrayInputStream(old)));
            same(frames,legacyRead(current));
            for (int length : new int[]{0,7,current.length-1}) expectIo(() -> TrackCodec.read(new ByteArrayInputStream(Arrays.copyOf(current,length))));
            byte[] trailing=Arrays.copyOf(current,current.length+1);
            expectIo(() -> TrackCodec.read(new ByteArrayInputStream(trailing)));
        }
        // Pixel counts enforce both underflow and expansion bounds, not just the archive byte quota.
        byte[] tiny=encode(List.of(frame(0,1,1,new int[]{0x0102feff})));
        int dimensions=8+2+"screen".length()+2+"minecraft:overworld".length()+8;
        byte[] under=tiny.clone(); java.nio.ByteBuffer.wrap(under).putInt(dimensions,2);
        expectIo(() -> TrackCodec.read(new ByteArrayInputStream(under)));
        byte[] excess=encode(List.of(frame(0,2,1,new int[]{1,2})));
        java.nio.ByteBuffer.wrap(excess).putInt(dimensions,1);
        expectIo(() -> TrackCodec.read(new ByteArrayInputStream(excess)));
    }
    private static void entryBounds() throws Exception {
        Archive archive=new Archive();byte[] encoded=encode(List.of(frame(0,1,1,new int[]{1})));
        archive.entries.put(TrackIndex.entry(0),encoded);
        TrackIndex exact=index(encoded.length);check(TrackFiles.chunk(archive.file,exact,0).size()==1,"exact entry succeeds");
        expectIo(() -> TrackFiles.readChunk(archive.file,index(encoded.length-1),0,()->false));
        expectIo(() -> TrackFiles.readChunk(archive.file,index(encoded.length+1),0,()->false));
        int reads=archive.reads.get();expectIo(() -> TrackFiles.readChunk(archive.file,exact,0,()->true));
        check(archive.reads.get()==reads,"cancelled read never opens an archive stream");
        archive.entries.remove(TrackIndex.entry(0));expectIo(() -> TrackFiles.chunk(archive.file,exact,0));
    }
    private static void detachedPlayback() throws Exception {
        Archive archive=new Archive();var writer=new TrackFiles.Writer(archive.file);
        writer.append(List.of(frame(0,1,1,new int[]{1})));writer.append(List.of(frame(5000,1,1,new int[]{2})));writer.finish();
        CountDownLatch decoding=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        var player=new PlaybackSession(archive.file,PlaybackSession.CACHE_BYTES,encoded->{
            check(!Thread.holdsLock(archive.file),"production playback invokes decoder outside archive monitor");
            check(archive.open.get()==0,"archive input is closed before CPU decode starts");
            if(calls.incrementAndGet()==2){decoding.countDown();awaitUninterruptibly(release);}
            return TrackFiles.decodeChunk(encoded);
        });
        ExecutorService jobs=Executors.newFixedThreadPool(2);
        try {
            check(player.prepareAt(0,2000),"initial playback");player.viewAt(5000);
            check(decoding.await(2,TimeUnit.SECONDS),"future CPU decode deliberately delayed");
            Future<?> audio=jobs.submit(()->{synchronized(archive.file){check(archive.open.get()==0,"parallel audio can acquire archive while decoder waits");}});
            audio.get(1,TimeUnit.SECONDS);
            check(player.viewAt(0).frames().getFirst().timestampMillis()==0,"backward seek remains exact during future decode");
            Future<?> close=jobs.submit(player::close);close.get(1,TimeUnit.SECONDS);
            check(player.viewAt(5000).status()==PlaybackView.Status.CLOSED,"close does not wait for detached CPU decode");
            int reads=archive.reads.get();release.countDown();jobs.shutdown();check(jobs.awaitTermination(2,TimeUnit.SECONDS),"helper jobs end");
            Thread.sleep(30);check(archive.reads.get()==reads&&player.cachedBytes()==0,"late decode cannot publish or reopen archive after close");
        } finally {release.countDown();player.close();jobs.shutdownNow();}
    }
    private static void closedBeforeRead() throws Exception {
        Archive archive=new Archive();AtomicBoolean cancelled=new AtomicBoolean();ExecutorService jobs=Executors.newSingleThreadExecutor();
        Future<?> pending;
        try {
            synchronized(archive.file){
                pending=jobs.submit(()->{try{TrackFiles.readChunk(archive.file,index(1),0,cancelled::get);throw new AssertionError("cancelled read succeeded");}catch(InterruptedIOException expected){}catch(IOException bad){throw new UncheckedIOException(bad);}});
                cancelled.set(true);
            }
            pending.get(2,TimeUnit.SECONDS);check(archive.reads.get()==0,"cancellation rechecked after archive lock acquisition");
        } finally {jobs.shutdownNow();}
    }
    /** No save/write/close ReplayFile operation is supported; the original ZIP remains read-only. */
    private static void realArchive(Path path) throws Exception {
        var before=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
        byte[] beforeHash=hash(path);
        try(ZipFile zip=new ZipFile(path.toFile())){
            ReplayFile file=(ReplayFile)Proxy.newProxyInstance(ReplayFile.class.getClassLoader(),new Class<?>[]{ReplayFile.class},(proxy,method,args)->{
                if(method.getName().equals("get")){check(Thread.holdsLock(proxy),"real archive access synchronized");var entry=zip.getEntry((String)args[0]);return Optional.fromNullable(entry==null?null:zip.getInputStream(entry));}
                if(method.getName().equals("toString"))return "ReadOnlyScreenPipelineProbe";
                throw new UnsupportedOperationException("Read-only archive: "+method.getName());
            });
            TrackIndex index=TrackFiles.index(file);List<String> audio=zip.stream().map(ZipEntry::getName).filter(name->name.startsWith("piq-audio/v1/")&&name.endsWith(".pcmz")).toList();
            check(!audio.isEmpty(),"real recording has audio chunks for parallel reads");
            AtomicBoolean running=new AtomicBoolean(true);List<Long> audioTimes=Collections.synchronizedList(new ArrayList<>());
            ExecutorService jobs=Executors.newSingleThreadExecutor();
            Future<?> reader=jobs.submit(()->{int n=0;while(running.get()){
                long start=System.nanoTime();synchronized(file){try(InputStream in=file.get(audio.get(n++%audio.size())).get()){
                    if(in.readNBytes(1_048_577).length>1_048_576)throw new IOException("Audio byte bound");
                }catch(IOException error){throw new UncheckedIOException(error);}}
                audioTimes.add(System.nanoTime()-start);try{Thread.sleep(1);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            }});
            List<Long> reads=new ArrayList<>(),bulk=new ArrayList<>(),scalar=new ArrayList<>(),writes=new ArrayList<>(),oldWrites=new ArrayList<>();int frames=0;
            try {
                for(int number=0;number<index.chunks().size();number++){
                    long start=System.nanoTime();byte[] encoded=TrackFiles.readChunk(file,index,number,()->false);reads.add(System.nanoTime()-start);
                    start=System.nanoTime();var decoded=TrackFiles.decodeChunk(encoded);bulk.add(System.nanoTime()-start);frames+=decoded.size();
                    start=System.nanoTime();var legacy=legacyRead(encoded);scalar.add(System.nanoTime()-start);same(legacy,decoded);
                    // Periodic representative chunks keep the optional probe bounded while testing all sizes.
                    if(number%8==0){start=System.nanoTime();byte[] now=encode(decoded);writes.add(System.nanoTime()-start);
                        start=System.nanoTime();byte[] old=legacyWrite(decoded);oldWrites.add(System.nanoTime()-start);
                        check(Arrays.equals(now,old),"real distribution writes stay byte identical");}
                }
            } finally {running.set(false);reader.get(5,TimeUnit.SECONDS);jobs.shutdownNow();}
            System.out.println("REAL_SCREEN_PIPELINE chunks="+index.chunks().size()+" frames="+frames+" audioEntries="+audio.size()+" audioReads="+audioTimes.size());
            report("archiveRead",reads);report("bulkDecode",bulk);report("scalarDecode",scalar);report("bulkEncode",writes);report("scalarEncode",oldWrites);report("parallelAudioRead",audioTimes);
        }
        var after=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
        check(before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&Arrays.equals(beforeHash,hash(path)),"real recording SHA/size/mtime unchanged");
    }
    private static void report(String name,List<Long> nanos){long[] values=nanos.stream().mapToLong(Long::longValue).sorted().toArray();System.out.printf(Locale.ROOT,"%s count=%d p50Ms=%.3f p90Ms=%.3f maxMs=%.3f totalMs=%.3f%n",name,values.length,values[values.length/2]/1e6,values[(values.length-1)*9/10]/1e6,values[values.length-1]/1e6,Arrays.stream(values).sum()/1e6);}
    private static byte[] hash(Path path)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))>=0;)digest.update(buffer,0,n);}return digest.digest();}
    private static byte[] encode(List<ScreenFrame> frames)throws IOException{var out=new ByteArrayOutputStream();TrackCodec.write(out,frames);return out.toByteArray();}
    // Independent scalar v1 oracle retained solely in test sources (never included in the player JAR).
    private static byte[] legacyWrite(List<ScreenFrame> frames)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0x50525131);out.writeInt(frames.size());
        for(var f:frames){out.writeUTF(f.source());out.writeUTF(f.dimension());out.writeLong(f.timestampMillis());out.writeInt(f.width());out.writeInt(f.height());out.writeInt(f.vertices().size());
            for(var v:f.vertices()){out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());out.writeFloat(v.u());out.writeFloat(v.v());out.writeInt(v.color());}
            var packed=new ByteArrayOutputStream();var deflater=new Deflater(Deflater.BEST_SPEED);try(var z=new DataOutputStream(new DeflaterOutputStream(packed,deflater))){for(int pixel:f.abgr())z.writeInt(pixel);}finally{deflater.end();}
            out.writeInt(packed.size());packed.writeTo(out);
        }return bytes.toByteArray();
    }
    private static List<ScreenFrame> legacyRead(byte[] encoded)throws IOException{
        var in=new DataInputStream(new ByteArrayInputStream(encoded));if(in.readInt()!=0x50525131)throw new IOException("magic");int count=in.readInt();var result=new ArrayList<ScreenFrame>();
        for(int n=0;n<count;n++){String source=in.readUTF(),dimension=in.readUTF();long time=in.readLong();int w=in.readInt(),h=in.readInt(),nv=in.readInt();var vertices=new ArrayList<ScreenVertex>();
            for(int v=0;v<nv;v++)vertices.add(new ScreenVertex(in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat(),in.readInt()));
            byte[] packed=in.readNBytes(in.readInt());int[] pixels=new int[w*h];try(var z=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))){for(int i=0;i<pixels.length;i++)pixels[i]=z.readInt();if(z.read()!=-1)throw new IOException("extra pixels");}
            result.add(new ScreenFrame(source,dimension,time,w,h,pixels,vertices));
        }if(in.read()!=-1)throw new IOException("extra chunk");return result;
    }
    private static void same(List<ScreenFrame> expected,List<ScreenFrame> actual){check(expected.size()==actual.size(),"frame count unchanged");for(int n=0;n<expected.size();n++){var a=expected.get(n);var b=actual.get(n);check(a.source().equals(b.source())&&a.dimension().equals(b.dimension())&&a.timestampMillis()==b.timestampMillis()&&a.width()==b.width()&&a.height()==b.height()&&a.vertices().equals(b.vertices())&&Arrays.equals(a.abgr(),b.abgr()),"all metadata and pixel bits unchanged");}}
    private static TrackIndex index(int length){return new TrackIndex(List.of(new TrackIndex.Ref("screen",0,0,0)),List.of(new TrackIndex.Chunk(0,length)));}
    private static ScreenFrame frame(long time,int w,int h,int[] pixels){return new ScreenFrame("screen","minecraft:overworld",time,w,h,pixels,List.of(new ScreenVertex(0,0,0,0,0,-1),new ScreenVertex(1,0,0,1,0,-1),new ScreenVertex(1,1,0,1,1,-1),new ScreenVertex(0,1,0,0,1,-1)));}
    private static void awaitUninterruptibly(CountDownLatch latch){boolean interrupted=false;for(;;)try{latch.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    private interface IoAction{void run()throws IOException;}
    private static void expectIo(IoAction action)throws Exception{try{action.run();throw new AssertionError("Invalid input accepted");}catch(IOException expected){CHECKS.incrementAndGet();}}
    private static void check(boolean value,String message){CHECKS.incrementAndGet();if(!value)throw new AssertionError(message);}
    private static final class Archive implements InvocationHandler{
        final Map<String,byte[]> entries=new ConcurrentHashMap<>();final AtomicInteger reads=new AtomicInteger(),open=new AtomicInteger();
        final ReplayFile file=(ReplayFile)Proxy.newProxyInstance(ReplayFile.class.getClassLoader(),new Class<?>[]{ReplayFile.class},this);
        public Object invoke(Object proxy,Method method,Object[] args){return switch(method.getName()){
            case "get"->{check(Thread.holdsLock(proxy),"archive get holds monitor");reads.incrementAndGet();byte[] bytes=entries.get(args[0]);if(bytes==null)yield Optional.absent();open.incrementAndGet();yield Optional.of(new ByteArrayInputStream(bytes){public void close(){check(Thread.holdsLock(proxy),"archive stream closed under monitor");open.decrementAndGet();}});}
            case "write"->new ByteArrayOutputStream(){public void close(){entries.put((String)args[0],toByteArray());}};
            case "toString"->"PipelineTestArchive";
            default->throw new UnsupportedOperationException(method.getName());};}
    }
}
