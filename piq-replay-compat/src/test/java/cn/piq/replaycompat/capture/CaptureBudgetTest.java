package cn.piq.replaycompat.capture;

import static cn.piq.replaycompat.capture.CaptureAssertions.*;

class CaptureBudgetTest {
    public static void main(String[] args) {
        var tests=new CaptureBudgetTest();
        tests.admitsTwentyFramesPerSecondWithoutLosingVisibleSource();
        tests.renderJitterDoesNotAccumulateSamplingDrift();
        tests.stallSkipsMissedIntervalsWithoutBurst();
        tests.boundsConcurrentSourcesAndReusesExpiredSlots();
        tests.rewindExpiresOldGenerationAndResetReleasesBudget();
        tests.positionIdentityToleratesCameraFloatNoiseButSeparatesMachines();
        tests.rejectsOverflowAndUnboundedImages();
        System.out.println("CaptureBudgetTest: 7 behavioral cases passed");
    }
    void admitsTwentyFramesPerSecondWithoutLosingVisibleSource() {
        var budget=new CaptureBudget();
        assertTrue(budget.admit("one",0));
        for(int t=1;t<50;t++) assertFalse(budget.admit("one",t));
        assertTrue(budget.admit("one",50));
        assertTrue(budget.admit("one",100));
        assertFalse(budget.admit("one",140));
        assertTrue(budget.expired(440).isEmpty());
        assertEquals(java.util.List.of("one"),budget.expired(441));
        assertTrue(budget.expired(442).isEmpty());
    }
    void renderJitterDoesNotAccumulateSamplingDrift() {
        for(int renderFps:new int[]{30,60,75,144}) {
            var budget=new CaptureBudget();int captured=0;
            for(int frame=0;frame<renderFps*10;frame++) {
                long time=(long)(frame*1000.0/renderFps);
                if(budget.admit("one",time)) captured++;
            }
            assertEquals(200,captured);
        }
        var budget=new CaptureBudget();
        assertTrue(budget.admit("one",0));
        assertTrue(budget.admit("one",63));
        assertFalse(budget.admit("one",99));
        assertTrue(budget.admit("one",100));
    }
    void stallSkipsMissedIntervalsWithoutBurst() {
        var budget=new CaptureBudget();
        assertTrue(budget.admit("one",0));
        assertTrue(budget.admit("one",1037));
        assertFalse(budget.admit("one",1037));
        assertFalse(budget.admit("one",1049));
        assertTrue(budget.admit("one",1050));
        assertTrue(budget.admit("one",10));
        assertFalse(budget.admit("one",59));
        assertTrue(budget.admit("one",60));
        assertEquals(1050L,CaptureBudget.nextFrame(1037,50));
    }
    void boundsConcurrentSourcesAndReusesExpiredSlots() {
        var budget=new CaptureBudget();
        for(int i=0;i<16;i++)assertTrue(budget.admit("screen-"+i,0));
        assertFalse(budget.admit("overflow",0));
        assertEquals(16,budget.expired(301).size());
        assertTrue(budget.admit("overflow",301));
    }
    void rewindExpiresOldGenerationAndResetReleasesBudget() {
        var budget=new CaptureBudget();
        assertTrue(budget.admit("old",1000));
        assertEquals(java.util.List.of("old"),budget.expired(20));
        assertTrue(budget.admit("old",20));
        budget.clear();assertTrue(budget.expired(10000).isEmpty());
    }
    void positionIdentityToleratesCameraFloatNoiseButSeparatesMachines() {
        assertEquals(CaptureBudget.source(4.5,64.75,-23.5),CaptureBudget.source(4.500001,64.749999,-23.500001));
        assertNotEquals(CaptureBudget.source(4.5,64.75,-23.5),CaptureBudget.source(5.5,64.75,-23.5));
        assertThrows(IllegalArgumentException.class,()->CaptureBudget.source(Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->CaptureBudget.source(40_000_000,0,0));
    }
    void rejectsOverflowAndUnboundedImages() {
        assertTrue(CaptureBudget.validPixels(1024,1024));
        assertTrue(CaptureBudget.validPixels(640,480));
        assertFalse(CaptureBudget.validPixels(0,10));
        assertFalse(CaptureBudget.validPixels(1025,1));
        assertFalse(CaptureBudget.validPixels(Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
}
