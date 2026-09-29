package cn.piq.fcarcade.client.performance;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FramePerformanceTest {
    @Test void disabledFramePathNeverReadsClock() {
        var clock=new AtomicLong(); var p=new FramePerformance(()->{clock.incrementAndGet();return 0;});
        assertEquals(Long.MIN_VALUE,p.begin());p.completed(Long.MIN_VALUE);
        assertFalse(p.sample().enabled());assertEquals(0,clock.get());
    }
    @Test void oneSecondCountsActualFramesAndCallCostsNotLifetimeCounter() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        for(int bin=1;bin<=10;bin++)for(int j=0;j<6;j++){
            clock.set(bin*100_000_000L+j*10_000_000L);long start=p.begin();clock.addAndGet(2_000_000);p.completed(start);
        }
        clock.set(1_100_000_000L);var s=p.sample();
        assertTrue(s.ready());assertEquals(60,s.fps());assertEquals(2,s.averageMs());assertEquals(2,s.maximumMs());
    }
    @Test void warmupCannotPretendToBeStableFps() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        p.completed(p.begin());clock.set(900_000_000);assertFalse(p.sample().ready());
    }
    @Test void idleAndLongStallExpireOldFramesWithoutOwnerHeartbeat() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        clock.set(500_000_000);p.completed(p.begin());clock.set(1_100_000_000L);assertEquals(1,p.sample().fps());
        clock.set(10_000_000_000L);var s=p.sample();assertTrue(s.ready());assertEquals(0,s.fps());assertEquals(0,s.averageMs());
    }
    @Test void reenableOrResetRejectsAnOldInFlightCall() {
        var clock=new AtomicLong(100);var p=new FramePerformance(clock::get);p.enabled(true);long old=p.begin();
        clock.set(200);p.enabled(false);p.enabled(true);p.completed(old);clock.set(2_000_000_000L);assertEquals(0,p.sample().fps());
        old=p.begin();clock.incrementAndGet();p.reset();p.completed(old);clock.set(4_000_000_000L);assertEquals(0,p.sample().fps());
    }
    @Test void completedBinsExcludePartialCurrentBinAndHaveBoundedHistory() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        for(int i=1;i<=100;i++){clock.set(i*100_000_000L+1);p.completed(p.begin());}
        assertEquals(10,p.sample().fps());
    }
    @Test void maximumSeparatesSlowCallFromAverage() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        clock.set(200_000_000);long start=p.begin();clock.addAndGet(2_000_000);p.completed(start);
        start=p.begin();clock.addAndGet(8_000_000);p.completed(start);clock.set(1_100_000_000L);
        assertEquals(5,p.sample().averageMs());assertEquals(8,p.sample().maximumMs());
    }
    @Test void repeatedEnableDoesNotRestartWindow() {
        var clock=new AtomicLong();var p=new FramePerformance(clock::get);p.enabled(true);
        clock.set(1_100_000_000L);p.enabled(true);assertTrue(p.sample().ready());
    }
}
