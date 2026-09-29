package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcPlaybackPacingTest {
    @Test void ordinaryBatchesRetainNormalPacing() {
        var p = new SfcPlaybackPacing();
        for (int n=1;n<=SfcPlaybackPacing.ENTER_BACKLOG;n++) assertFalse(p.frame(n, 10, false).catchingUp());
    }
    @Test void delayedOneAndThreeSecondBatchesConvergeWithoutSkippingAnyInputFrame() {
        for (int delayedFrames : new int[]{60, 180}) {
            var p = new SfcPlaybackPacing(); int executed = 0, yields = 0, muted = 0;
            for (int outstanding=delayedFrames;outstanding>0;outstanding--) {
                var step = p.frame(outstanding, executed * 500_000L, false);
                if (executed==0) assertTrue(step.entered()); else assertFalse(step.entered());
                assertFalse(step.expired());
                if (step.yieldNanos()>0) { yields++; assertEquals(SfcPlaybackPacing.YIELD_NANOS,step.yieldNanos()); }
                if (outstanding>SfcPlaybackPacing.TARGET_BACKLOG) { assertTrue(step.catchingUp()); muted++; }
                else { assertFalse(step.catchingUp()); assertEquals(outstanding==SfcPlaybackPacing.TARGET_BACKLOG,step.recovered()); }
                executed++; // Production also invokes the core once for every iteration.
            }
            assertEquals(delayedFrames,executed); assertEquals(delayedFrames-3,muted); assertTrue(yields>0);
        }
    }
    @Test void frameBudgetAndTimeBudgetBothYieldCpu() {
        var frames = new SfcPlaybackPacing();
        for (int i=0;i<12;i++) assertEquals(0,frames.frame(100, i, false).yieldNanos());
        assertTrue(frames.frame(100,12,false).yieldNanos()>0);
        var clock = new SfcPlaybackPacing(); clock.frame(100,0,false);
        assertTrue(clock.frame(100,SfcPlaybackPacing.MAX_BURST_NANOS,false).yieldNanos()>0);
    }
    @Test void sustainedOverloadHasAnExplicitBoundAndRepairOwnsItsSeparateClock() {
        var p = new SfcPlaybackPacing(); p.frame(100,0,false);
        assertTrue(p.frame(100,SfcPlaybackPacing.MAX_CATCH_UP_NANOS,false).expired());
        var repairing = p.frame(100,SfcPlaybackPacing.MAX_CATCH_UP_NANOS,true);
        assertFalse(repairing.expired()); assertFalse(repairing.catchingUp());
        assertFalse(p.frame(3,SfcPlaybackPacing.MAX_CATCH_UP_NANOS+1,false).recovered());
        assertTrue(p.frame(100,SfcPlaybackPacing.MAX_CATCH_UP_NANOS+2,false).entered());
    }
    @Test void resetStartsFreshAcrossSnapshotReplacementAndInputMustIncludeTheCurrentFrame() {
        var p=new SfcPlaybackPacing(); p.frame(100,0,false); p.reset();
        assertTrue(p.frame(100,SfcPlaybackPacing.MAX_CATCH_UP_NANOS+1,false).entered());
        assertThrows(IllegalArgumentException.class,()->p.frame(0,0,false));
    }
}
