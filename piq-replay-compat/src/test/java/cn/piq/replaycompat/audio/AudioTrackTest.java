package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import cn.piq.replaycompat.replay.TrackCutPlan;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;
import static cn.piq.replaycompat.audio.AudioTestSupport.*;

public final class AudioTrackTest {
    public static void main(String[] args) throws Exception {
        byte[] original = pcm(480, 2, 1234);
        AudioFrame immutable = new AudioFrame("terminal-0", 100_000, 48_000, 2, original);
        original[0] = 0;
        check(immutable.sample(0, 0) == 1234, "frame owns capture copy");
        byte[] accessor = immutable.pcm(); accessor[0] = 0;
        check(immutable.sample(0, 0) == 1234, "frame accessor cannot mutate stored PCM");
        check(immutable.endMicros() == 110_000 && immutable.sampleFrames() == 480, "native sample duration");
        check(immutable.slice(102_000, 105_000, 0).sampleFrames() == 144, "cut at sample boundaries");
        check(immutable.slice(102_000, 105_000, 0).startMicros() == 0, "cut remaps time");
        check(immutable.slice(120_000, 130_000, 0) == null, "disjoint cut empty");
        int invalid = 0;
        for (Runnable bad : List.<Runnable>of(
                () -> new AudioFrame("bad path", 0, 48000, 2, pcm(1,2,1)),
                () -> new AudioFrame("ok", -1, 48000, 2, pcm(1,2,1)),
                () -> new AudioFrame("ok", 0, 7999, 2, pcm(1,2,1)),
                () -> new AudioFrame("ok", 0, 192001, 2, pcm(1,2,1)),
                () -> new AudioFrame("ok", 0, 48000, 3, pcm(1,2,1)),
                () -> new AudioFrame("ok", 0, 48000, 2, new byte[3]),
                () -> new AudioFrame("ok", 0, 48000, 2, new byte[65540]),
                () -> new AudioFrame("ok", 0, 8000, 1, new byte[8002]),
                () -> new AudioFrame("ok", AudioFrame.MAX_TIME_MICROS, 48000, 2, pcm(1,2,1)))) {
            try { bad.run(); } catch (IllegalArgumentException expected) { invalid++; }
        }
        check(invalid == 9, "format/size/time validation");
        byte[] mixed = PcmMixer.render(List.of(immutable), 4800, 480);
        check(sample(mixed, 0) == 1234 && sample(mixed, 959) == 1234, "recorded gain not reapplied");
        check(sample(PcmMixer.render(List.of(), 0, 10), 0) == 0, "missing time is silence");
        var mono = new AudioFrame("mono", 0, 44100, 1, pcm(441,1,7654));
        byte[] stereo = PcmMixer.render(List.of(mono), 0, 480);
        check(sample(stereo,0) == 7654 && sample(stereo,959) == 7654, "44.1 mono resampled and duplicated");
        var loudA = new AudioFrame("a",0,48000,2,pcm(10,2,30000));
        var loudB = new AudioFrame("b",0,48000,2,pcm(10,2,30000));
        check(sample(PcmMixer.render(List.of(loudA,loudB),0,10),0) == 32767, "bounded mix saturates rather than wraps");
        check(sample(PcmMixer.render(List.of(loudA,loudA),0,10),0) == 30000, "same source overlap never doubles");

        Archive archive = new Archive(300);
        var writer = new AudioTrackFiles.Writer(archive.file);
        writer.append(List.of(immutable, mono)); var summary = writer.finish(2,0,"complete");
        check(summary.frames() == 2 && summary.sources() == 2, "writer summary");
        check(archive.entries.size() == 3, "only chunk/index/bounded diagnostic");
        var index = AudioTrackFiles.readIndex(archive.file);
        check(index.refs().size() == 1 && index.between(101000,105000).size() == 1, "bounded index selection");
        var decoded = AudioTrackFiles.readChunk(archive.file,index.refs().get(0));
        check(decoded.size() == 2 && decoded.get(0).sample(0,0) == 1234 && decoded.get(1).sampleRate() == 44100, "compressed PCM round trip");
        writer.append(List.of(immutable));
        check(writer.finish(2,0,"complete").equals(summary), "finalized writer cannot append late data");
        byte[] savedIndex = archive.entries.get(AudioTrackFiles.INDEX).clone();
        byte[] savedChunk = archive.entries.get(AudioTrackFiles.entry(0)).clone();
        archive.entries.put(AudioTrackFiles.INDEX, Arrays.copyOf(savedIndex,savedIndex.length+1));
        expectIo(() -> AudioTrackFiles.readIndex(archive.file), "trailing index rejected");
        archive.entries.put(AudioTrackFiles.INDEX,savedIndex);
        byte[] corrupt = savedChunk.clone(); corrupt[corrupt.length/2] ^= 1; archive.entries.put(AudioTrackFiles.entry(0),corrupt);
        expectIo(() -> AudioTrackFiles.readChunk(archive.file,index.refs().get(0)), "chunk checksum mismatch rejected");
        archive.entries.put(AudioTrackFiles.entry(0),Arrays.copyOf(savedChunk,savedChunk.length-1));
        expectIo(() -> AudioTrackFiles.readChunk(archive.file,index.refs().get(0)), "truncated chunk rejected");
        archive.entries.put(AudioTrackFiles.entry(0),savedChunk);
        byte[] trailingPacked=Arrays.copyOf(savedChunk,savedChunk.length+1);CRC32 trailingCrc=new CRC32();trailingCrc.update(trailingPacked);
        archive.entries.put(AudioTrackFiles.entry(0),trailingPacked);
        var trailingRef=new AudioTrackFiles.Ref(0,index.refs().get(0).fromMicros(),index.refs().get(0).toMicros(),trailingPacked.length,index.refs().get(0).rawBytes(),2,trailingCrc.getValue());
        expectIo(()->AudioTrackFiles.readChunk(archive.file,trailingRef),"trailing compressed bytes rejected even with matching checksum");
        archive.entries.put(AudioTrackFiles.entry(0),savedChunk);
        byte[] invalidIndex = savedIndex.clone(); invalidIndex[7] = (byte)255;
        archive.entries.put(AudioTrackFiles.INDEX,invalidIndex);
        expectIo(() -> AudioTrackFiles.readIndex(archive.file), "declared index entries cannot overread");
        archive.entries.put(AudioTrackFiles.INDEX,savedIndex);
        var wrong = new AudioTrackFiles.Ref(0,1,index.refs().get(0).toMicros(),savedChunk.length,index.refs().get(0).rawBytes(),2,index.refs().get(0).crc());
        expectIo(() -> AudioTrackFiles.readChunk(archive.file,wrong), "chunk identity must match index");
        Archive missing = new Archive(100); check(AudioTrackFiles.readIndex(missing.file) == null,"old replay without track");
        archive.entries.put(AudioTrackFiles.WARNING,"incomplete".getBytes());
        expectIo(()->AudioTrackFiles.readIndex(archive.file),"explicit incomplete warning rejects partial track");
        archive.entries.put(AudioTrackFiles.WARNING,new byte[4097]);
        expectIo(()->AudioTrackFiles.readIndex(archive.file),"warning cannot exceed4KiB");archive.entries.remove(AudioTrackFiles.WARNING);

        AtomicLong clock = new AtomicLong(100); Object source = new Object();
        Archive recorded = new Archive(300); AudioRecording recorder = new AudioRecording(recorded.file,clock::get);
        Object first = recorder.token(source);
        recorder.capture(first,source,pcm(480,2,1000),0,1920,48000,2);
        recorder.reset(source,false);
        recorder.capture(first,source,pcm(480,2,2222),0,1920,48000,2);
        clock.set(120); Object second = recorder.token(source);
        recorder.capture(second,source,pcm(480,2,2000),0,1920,48000,2);
        recorder.reset(source,true); check(recorder.token(source) == null,"closed terminal cannot acquire a new token");
        recorder.capture(second,source,pcm(480,2,3333),0,1920,48000,2);
        recorder.stopAccepting(); check(recorder.token(new Object()) == null,"stop invalidates acquisition");
        var recordSummary = recorder.finish();
        check(recordSummary.frames() == 2,"flush/close reject in-flight old writes");
        var recordedIndex = AudioTrackFiles.readIndex(recorded.file);
        var recordedFrames = AudioTrackFiles.readChunk(recorded.file,recordedIndex.refs().get(0));
        check(recordedFrames.get(0).startMicros() == 100000 && recordedFrames.get(1).startMicros() == 120000,"reset preserves actual timeline gaps");
        Archive later = new Archive(100); AudioRecording next = new AudioRecording(later.file,()->0L);
        next.capture(first,source,pcm(480,2,4444),0,1920,48000,2);
        check(next.finish().frames() == 0,"old recording token cannot enter next recording");
        Archive failedArchive=new Archive(100);failedArchive.failWrite=AudioTrackFiles.INDEX;
        AudioRecording failedRecorder=new AudioRecording(failedArchive.file,()->0L);
        check(failedRecorder.finish()==null,"optional index failure never escapes to primary recorder");

        Archive many = new Archive(100); AudioRecording bounded = new AudioRecording(many.file,()->0L);
        for (int i=0;i<9;i++) { Object owner=new Object(); bounded.capture(bounded.token(owner),owner,pcm(48,2,100),0,192,48000,2); }
        check(bounded.finish().sources() == 8,"concurrent source quota");
        check(new String(many.entries.get(AudioTrackFiles.ROOT+"diagnostic.txt")).contains("partial_capture"),"source overflow recorded, not silent success");

        Archive pressured = new Archive(100000); pressured.writeEntered=new CountDownLatch(1); pressured.writeRelease=new CountDownLatch(1);
        AtomicLong pressureClock=new AtomicLong(); AudioRecording busy=new AudioRecording(pressured.file,pressureClock::get); Object terminal=new Object();
        byte[] large=pcm(15000,2,1000);
        for(int i=0;i<9;i++){pressureClock.set(i*400L);busy.capture(busy.token(terminal),terminal,large,0,large.length,48000,2);}
        check(pressured.writeEntered.await(2,TimeUnit.SECONDS),"recording IO gate entered");
        long start=System.nanoTime();
        for(int i=9;i<100;i++){pressureClock.set(i*400L);busy.capture(busy.token(terminal),terminal,large,0,large.length,48000,2);}
        check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"capture remains bounded while archive IO stalls");
        pressured.writeRelease.countDown(); var busySummary=busy.finish();
        check(busySummary.frames()<100,"backpressure drops optional PCM rather than blocking original write");
        check(new String(pressured.entries.get(AudioTrackFiles.ROOT+"diagnostic.txt")).contains("partial_capture"),"PCM backpressure persists diagnostic");

        Archive continuous=new Archive(100); AudioRecording fractional=new AudioRecording(continuous.file,()->0L); Object monoSource=new Object();
        for(int i=0;i<10;i++)fractional.capture(fractional.token(monoSource),monoSource,pcm(441,1,777),0,882,44100,1);
        fractional.finish(); var ci=AudioTrackFiles.readIndex(continuous.file); var cf=AudioTrackFiles.readChunk(continuous.file,ci.refs().get(0));
        check(cf.get(9).startMicros()==90000,"sample clock continuity independent of callback cadence");

        Archive cutSource=new Archive(100); var cutWriter=new AudioTrackFiles.Writer(cutSource.file);
        cutWriter.append(List.of(new AudioFrame("a",0,48000,2,pcm(4800,2,4567))));cutWriter.finish(1,0,"complete");
        TrackCutPlan plan=TrackCutPlan.create(100,List.of(new TrackCutPlan.Marker(20,TrackCutPlan.START_CUT),new TrackCutPlan.Marker(60,TrackCutPlan.END_CUT)));
        Archive cutOutput=new Archive(60);
        check(AudioBridge.copySegments(cutSource.file,cutOutput.file,plan.output(0)),"marker cuts map audio");
        var cutIndex=AudioTrackFiles.readIndex(cutOutput.file);var cutFrames=AudioTrackFiles.readChunk(cutOutput.file,cutIndex.refs().get(0));
        check(cutFrames.size()==2 && cutFrames.get(0).sampleFrames()==960 && cutFrames.get(1).sampleFrames()==1920,"cut truncates PCM samples, not timestamp only");
        check(cutFrames.get(1).startMicros()==20000 && cutFrames.get(1).endMicros()==60000,"post-cut PCM has affine output clock");
        TrackCutPlan split=TrackCutPlan.create(100,List.of(new TrackCutPlan.Marker(50,TrackCutPlan.SPLIT)));
        Archive right=new Archive(50);check(AudioBridge.copySegments(cutSource.file,right.file,split.output(1)),"split second part copied");
        var ri=AudioTrackFiles.readIndex(right.file);var rf=AudioTrackFiles.readChunk(right.file,ri.refs().get(0));
        check(rf.size()==1 && rf.get(0).startMicros()==0 && rf.get(0).sampleFrames()==2400,"split carries clipped continuous audio from boundary");
        Archive noAudio=new Archive(100), noAudioOut=new Archive(100);
        check(AudioBridge.copySegments(noAudio.file,noAudioOut.file,plan.output(0)) && noAudioOut.entries.isEmpty(),"old silent recordings are not invented");
        System.out.println("AudioTrackTest: "+checks+" checks passed");
    }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
    private static void expectIo(IoAction action,String name)throws Exception { boolean rejected=false;try{action.run();}catch(IOException expected){rejected=true;}check(rejected,name); }
}
