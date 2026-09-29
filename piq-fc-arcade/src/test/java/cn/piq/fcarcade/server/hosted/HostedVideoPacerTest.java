package cn.piq.fcarcade.server.hosted;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedVideoPacerTest {
    @Test void fiveMillisecondPollingMaintainsEachCapWithoutSlowingToFiftyFps() {
        for(int fps:new int[]{20,30,60}) {
            var pacer=new HostedVideoPacer();int emitted=0;
            for(long now=0;now<1_000_000_000L;now+=5_000_000L)if(pacer.take(now,fps))emitted++;
            assertEquals(fps,emitted,"poll clock must preserve "+fps+" FPS phase");
        }
    }
    @Test void longStallDropsTimeDebtInsteadOfBurstingOldVideo() {
        var pacer=new HostedVideoPacer();assertTrue(pacer.take(0,60));
        assertTrue(pacer.take(5_000_000_000L,60));
        for(int i=0;i<200;i++)assertFalse(pacer.take(5_000_000_000L,60));
        assertFalse(pacer.take(5_010_000_000L,60));assertTrue(pacer.take(5_020_000_000L,60));
    }
    @Test void changingRateInRunningSessionDoesNotEmitExtraFrameAtChange() {
        var pacer=new HostedVideoPacer();assertTrue(pacer.take(0,20));
        assertFalse(pacer.take(10_000_000L,60));assertFalse(pacer.take(25_000_000L,60));
        assertTrue(pacer.take(30_000_000L,60));
        assertFalse(pacer.take(30_000_000L,30));assertFalse(pacer.take(60_000_000L,30));
        assertTrue(pacer.take(65_000_000L,30));
    }
    @Test void rejectsUnsupportedCaps() {
        for(int fps:new int[]{-1,0,1,21,25,59,120})assertThrows(IllegalArgumentException.class,()->new HostedVideoPacer().take(0,fps));
    }
}
