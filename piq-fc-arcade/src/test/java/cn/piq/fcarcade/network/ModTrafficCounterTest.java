package cn.piq.fcarcade.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModTrafficCounterTest {
    @Test void reportsBytesPerRealSecondAndPerStreamFpsNotRenderOrTickRate() {
        var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);var one=UUID.randomUUID();var two=UUID.randomUUID();
        count.upload(1024);count.download(8192);
        for(int i=0;i<30;i++)count.video(one);for(int i=0;i<20;i++)count.video(two);
        clock.set(1_000_000_000L);var sample=count.sample();
        assertEquals(1024,sample.uploadBytesPerSecond());assertEquals(8192,sample.downloadBytesPerSecond());
        assertEquals(30,sample.streams().get(0).fps());assertEquals(20,sample.streams().get(1).fps());
        clock.set(2_000_000_000L);assertEquals(0,count.sample().uploadBytesPerSecond());
        clock.set(3_000_000_000L);assertTrue(count.sample().streams().isEmpty());
    }
    @Test void streamCardinalityIsBoundedAndExpiredKeysReclaimed() {
        var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);
        for(int i=0;i<1000;i++)count.video(UUID.randomUUID());
        clock.set(1_000_000_000L);assertEquals(ModTrafficCounter.MAX_STREAMS,count.sample().streams().size());
        clock.set(4_000_000_000L);count.video(UUID.randomUUID());assertEquals(1,count.sample().streams().size());
    }
    @Test void staleMediaWorkerCannotPolluteNewConnection() {
        var clock=new AtomicLong();var old=new ModTrafficCounter(clock::get);var fresh=new ModTrafficCounter(clock::get);
        try {
            ModTrafficProbe.clientCollector(old);var oldMeter=ModTrafficProbe.videoMeter(UUID.randomUUID());oldMeter.run();
            ModTrafficProbe.clientCollector(fresh);oldMeter.run();ModTrafficProbe.videoMeter(UUID.randomUUID()).run();
            clock.set(1_000_000_000L);assertEquals(1,old.sample().streams().getFirst().fps());assertEquals(1,fresh.sample().streams().getFirst().fps());
        } finally { ModTrafficProbe.clientCollector(null); }
    }
    @Test void countersAreSafeAcrossEncoderAndDecoderThreads()throws Exception {
        var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);
        var threads=new Thread[4];for(int t=0;t<threads.length;t++)threads[t]=Thread.ofPlatform().start(()->{for(int i=0;i<10000;i++){count.upload(3);count.download(5);}});
        for(var thread:threads)thread.join();clock.set(1_000_000_000L);
        assertEquals(120000,count.sample().uploadBytesPerSecond());assertEquals(200000,count.sample().downloadBytesPerSecond());
        assertEquals(120000,count.sample().uploadedBytes());assertEquals(200000,count.sample().downloadedBytes());
    }
    @Test void totalsAreImmediateAndSurviveSamplingSilenceAndClockReversal() {
        var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);
        count.upload(100);count.download(200);count.upload(-1);count.download(0);
        assertEquals(300,count.sample().totalBytes());
        clock.set(1_000_000_000L);assertEquals(100,count.sample().uploadBytesPerSecond());
        count.upload(50);assertEquals(150,count.sample().uploadedBytes());
        clock.set(9_000_000_000L);assertEquals(350,count.sample().totalBytes());
        clock.set(0);assertEquals(350,count.sample().totalBytes());assertEquals(0,count.sample().uploadBytesPerSecond());
    }
    @Test void lifetimeCountersAndCombinedTotalSaturateRatherThanWrap()throws Exception {
        var count=new ModTrafficCounter(()->0);var up=ModTrafficCounter.class.getDeclaredField("totalUpload");
        var down=ModTrafficCounter.class.getDeclaredField("totalDownload");up.setAccessible(true);down.setAccessible(true);
        up.setLong(count,Long.MAX_VALUE-3);down.setLong(count,Long.MAX_VALUE-2);
        count.upload(10);count.download(10);var sample=count.sample();
        assertEquals(Long.MAX_VALUE,sample.uploadedBytes());assertEquals(Long.MAX_VALUE,sample.downloadedBytes());
        assertEquals(Long.MAX_VALUE,sample.totalBytes());
    }
}
