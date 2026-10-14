package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import java.util.List;
import java.util.concurrent.*;
import static cn.piq.replaycompat.audio.AudioTestSupport.*;

public final class AudioPlaybackTest {
    public static void main(String[] args) throws Exception {
        Archive archive=new Archive(200);
        var writer=new AudioTrackFiles.Writer(archive.file);
        writer.append(List.of(new AudioFrame("source",0,48000,2,pcm(9600,2,5432))));writer.finish(1,0,"complete");
        Sink sink=new Sink();AudioPlayback playback=new AudioPlayback(archive.file,sink);
        playback.tick(0,false,1,false);
        await(()->sink.offers()>0,"asynchronous 1x decode reaches injected sink");
        check(sample(sink.first(),0)==5432,"decoded PCM reaches output without gain multiplication");
        check(sink.first().length==15360,"lookahead bounded to80ms");
        int before=sink.offers();playback.tick(10,true,1,false);
        check(sink.queued()==0,"pause synchronously invalidates queued PCM");
        Thread.sleep(30);check(sink.offers()==before,"pause cannot refill output");
        playback.tick(10,false,2,false);Thread.sleep(30);
        check(sink.queued()==0 && sink.offers()==before,"non1x speed deliberately muted");
        playback.tick(10,false,1,true);Thread.sleep(30);
        check(sink.queued()==0 && sink.offers()==before,"export deliberately muted without IO");
        playback.tick(50,false,1,false);await(()->sink.offers()>before,"resuming1x restarts at current time");
        int afterResume=sink.offers();playback.seek();
        check(sink.queued()==0,"seek invalidates output immediately");
        Thread.sleep(30);check(sink.offers()==afterResume,"seek never refills old timestamp before next tick");
        playback.tick(0,false,1,false);await(()->sink.offers()>afterResume,"backward seek plays new requested time");
        playback.close();check(sink.closed && sink.queued()==0,"close clears device queue");
        int afterClose=sink.offers();playback.tick(100,false,1,false);Thread.sleep(20);
        check(sink.offers()==afterClose,"closed playback cannot restart output");

        Archive slow=new Archive(200);slow.entries.putAll(archive.entries);slow.readEntered=new CountDownLatch(1);slow.readRelease=new CountDownLatch(1);
        Sink slowSink=new Sink();AudioPlayback loading=new AudioPlayback(slow.file,slowSink);
        long start=System.nanoTime();loading.tick(0,false,1,false);
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"tick returns without waiting for IO");
        check(slow.readEntered.await(2,TimeUnit.SECONDS),"slow archive gate reached");
        start=System.nanoTime();loading.tick(100,true,1,false);loading.seek();loading.close();
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"pause seek close never join stalled archive IO");
        slow.readRelease.countDown();Thread.sleep(40);
        check(slowSink.offers()==0 && slowSink.closed,"late read afterclose cannot emit PCM");

        Archive stale=new Archive(200);stale.entries.putAll(archive.entries);stale.readEntered=new CountDownLatch(1);stale.readRelease=new CountDownLatch(1);
        Sink staleSink=new Sink();AudioPlayback switching=new AudioPlayback(stale.file,staleSink);switching.tick(0,false,1,false);
        check(stale.readEntered.await(2,TimeUnit.SECONDS),"stale decoder gate reached");
        switching.seek();switching.tick(150,true,1,false);stale.readRelease.countDown();Thread.sleep(40);
        check(staleSink.offers()==0,"late decode afterpausedseek rejected");
        switching.tick(150,false,1,false);await(()->staleSink.offers()>0,"next live generation decodes latest time");switching.close();

        Archive old=new Archive(100);Sink oldSink=new Sink();AudioPlayback oldPlayer=new AudioPlayback(old.file,oldSink);
        oldPlayer.tick(0,false,1,false);Thread.sleep(40);check(oldSink.offers()==0,"missing track never emits synthetic samples");oldPlayer.close();
        Archive broken=new Archive(100);broken.entries.put(AudioTrackFiles.INDEX,new byte[]{1,2,3});
        Sink brokenSink=new Sink();AudioPlayback brokenPlayer=new AudioPlayback(broken.file,brokenSink);
        brokenPlayer.tick(0,false,1,false);Thread.sleep(40);check(brokenSink.offers()==0,"broken track muted safely");brokenPlayer.close();
        var ownerField=AudioBridge.class.getDeclaredField("playback");ownerField.setAccessible(true);
        Sink ownerSink=new Sink();AudioPlayback owner=new AudioPlayback(old.file,ownerSink);
        ownerField.set(null,owner);AudioBridge.playbackReady(owner);String currentStatus=AudioBridge.status();
        AudioBridge.playbackMissing(loading);AudioBridge.playbackWarning(loading,"stale failure",null);
        check(AudioBridge.status().equals(currentStatus),"retired reader cannot overwrite current playback status");
        AudioBridge.deviceWarning(slowSink,"stale device failure",null);
        check(AudioBridge.status().equals(currentStatus),"retired device cannot overwrite current playback status");
        AudioBridge.close();
        recoverableRefusals(archive);
        pacedPlayback();
        System.out.println("AudioPlaybackTest: "+checks+" checks passed");
    }
    private static void recoverableRefusals(Archive archive) throws Exception {
        RefusingSink sink = new RefusingSink(); AudioPlayback player = new AudioPlayback(archive.file, sink);
        sink.result = PcmSink.OfferResult.BACKPRESSURE; player.tick(0, false, 1, false);
        await(() -> player.diagnostics().backpressure() == 1, "backpressure classified without corrupting track");
        check(!player.diagnostics().trackFailed() && !player.diagnostics().outputFailed(), "full queue is recoverable");
        Thread.sleep(40); check(player.diagnostics().backpressure() == 1, "refusal never spins or retries without a new tick");
        sink.result = PcmSink.OfferResult.ACCEPTED; player.tick(10, false, 1, false);
        await(() -> sink.accepted.offers() == 1, "next tick recovers without reopening replay");
        sink.result = PcmSink.OfferResult.STALE; player.tick(20, false, 1, false);
        await(() -> player.diagnostics().staleOffers() == 1, "stale generation separately classified");
        sink.result = PcmSink.OfferResult.ACCEPTED; player.tick(30, false, 1, false);
        await(() -> sink.accepted.offers() == 2, "stale generation recovers at next current clock");
        sink.result = PcmSink.OfferResult.DEVICE_RECOVERING; player.tick(40, false, 1, false);
        await(() -> player.diagnostics().deviceWaits() > 0, "device retry does not mark track broken");
        int offers = sink.accepted.offers(); player.tick(40, true, 1, false);
        sink.result = PcmSink.OfferResult.ACCEPTED; Thread.sleep(30);
        check(sink.accepted.offers() == offers && sink.accepted.queued() == 0, "paused recovery never restarts PCM");
        player.tick(100, false, 1, false); await(() -> sink.accepted.offers() > offers, "resume after device retry works");
        check(player.diagnostics().recoveries() == 3, "recovery counters count real accepted resumptions");
        sink.result = PcmSink.OfferResult.DEVICE_FAILED; player.tick(110, false, 1, false);
        await(() -> player.diagnostics().outputFailed(), "permanent device failure identified separately");
        check(!player.diagnostics().trackFailed(), "device failure is not reported as archive corruption"); player.close();
        Archive broken = new Archive(100); broken.entries.put(AudioTrackFiles.INDEX, new byte[]{0});
        AudioPlayback invalid = new AudioPlayback(broken.file, new Sink()); invalid.tick(0, false, 1, false);
        await(() -> invalid.diagnostics().trackFailed(), "real malformed archive remains fatal");
        check(!invalid.diagnostics().outputFailed(), "archive failure is distinct from device failure"); invalid.close();
    }
    private static final class RefusingSink implements PcmSink {
        final Sink accepted = new Sink(); volatile OfferResult result = OfferResult.ACCEPTED;
        public void reset(long generation) { accepted.reset(generation); }
        public boolean offer(long generation, byte[] pcm) { return offerResult(generation, pcm) == OfferResult.ACCEPTED; }
        public OfferResult offerResult(long generation, byte[] pcm) {
            return result == OfferResult.ACCEPTED ? accepted.offerResult(generation, pcm) : result;
        }
        public void close() { accepted.close(); }
    }
    private static void pacedPlayback() throws Exception {
        int runMillis = Integer.getInteger("piq.test.audio.pacedMillis", 18_000);
        check(runMillis >= 18_000 && runMillis <= 120_000, "paced test duration bounded18to120seconds");
        int archiveMillis = runMillis + 12_000;
        Archive archive = new Archive(archiveMillis); var writer = new AudioTrackFiles.Writer(archive.file);
        for (int millis = 0; millis < archiveMillis; millis += 100) {
            writer.append(List.of(new AudioFrame("clock", millis * 1000L, 48000, 1, pcm(4800, 1, 1000 + millis / 10))));
        }
        writer.finish(archiveMillis / 100, 0, "complete");
        PacedDevice device = new PacedDevice(); JavaSoundSink sink = new JavaSoundSink(format -> device.line);
        AudioPlayback player = new AudioPlayback(archive.file, sink);
        long start = System.nanoTime(); boolean congested = false, clientStalled = false; int iterations = 0;
        try {
            // Longer than the reported ~13s failure; both decoder and actual bounded sink participate.
            while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < runMillis) {
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (!congested && millis >= 3000) { device.stall(650); congested = true; }
                if (!clientStalled && millis >= 6500) { Thread.sleep(350); clientStalled = true; continue; }
                player.tick(millis, false, 1, false);
                Thread.sleep(new int[]{7, 13, 19, 31, 11}[iterations++ % 5]);
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(player.diagnostics().backpressure() > 0, "real paced device congestion reached bounded queue limit");
            check(player.diagnostics().recoveries() > 0, "paced congestion recovered without replay reopen");
            check(!player.diagnostics().trackFailed() && !player.diagnostics().outputFailed(), "sustained jittered 1x remains live");
            check(device.consumedFrames() > 48L * (runMillis - 4000), "consumer emitted sustained real-rate audio, not just enqueue success");
            check(device.consumedFrames() <= (duration + 30) * 48, "consumer never instantaneously drains future audio");
            check(Math.abs(device.lastSample() - (1000 + duration / 10)) <= 25, "after stalls output catches current clock within250ms");
            check(sink.diagnostics().peakQueuedBytes() <= JavaSoundSink.MAX_QUEUED_BYTES, "real paced queue stays160ms bounded");
            check(device.maxBufferedFrames() <= 960 && !device.wrongThread(), "real device calls stay daemon-owned with20ms buffer");
            int flushes = device.flushes(); player.tick(duration, true, 1, false);
            await(() -> device.flushes() > flushes, "pause flushes paced hardware");
            int pausedWrites = device.writes(); Thread.sleep(120);
            check(device.writes() == pausedWrites, "paused paced output emits no queued tail");
            player.tick(duration, false, 2, false); Thread.sleep(70);
            check(device.writes() == pausedWrites, "non1x cannot revive recovered audio");
            player.tick(duration, false, 1, true); Thread.sleep(70);
            check(device.writes() == pausedWrites, "export cannot revive recovered audio");
            player.seek(); player.tick(1000, false, 1, false);
            await(() -> device.writes() > pausedWrites, "seek resumes at new timestamp");
            check(device.lastSample() == 1100, "backward seek never emits old20second PCM");
            System.out.println("Paced playback: wallMillis=" + duration + ", consumedFrames=" + device.consumedFrames()
                    + ", backpressure=" + player.diagnostics().backpressure() + ", recoveries=" + player.diagnostics().recoveries());
        } finally { player.close(); }
        await(device::closed, "paced device closes asynchronously");
    }
}
