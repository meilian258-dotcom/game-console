package cn.piq.replaycompat.capture;

import static cn.piq.replaycompat.capture.CaptureAssertions.*;

public final class ReplayRetirementTest {
    public static void main(String[] args) {
        var retirement = new ReplayRetirement();
        int[] stops = {0};
        Runnable close = () -> stops[0]++;
        assertFalse(retirement.block(false,close));
        assertEquals(0,stops[0]);
        assertTrue(retirement.block(true,close));
        assertEquals(1,stops[0]);
        for(int tick=0;tick<100;tick++) assertTrue(retirement.block(true,close));
        assertEquals(1,stops[0]);
        assertFalse(retirement.block(false,close));
        assertTrue(retirement.block(true,close));
        assertEquals(2,stops[0]);

        var retry = new ReplayRetirement();
        assertThrows(IllegalStateException.class,()->retry.block(true,()->{throw new IllegalStateException();}));
        assertTrue(retry.block(true,close));
        assertEquals(3,stops[0]);
        System.out.println("ReplayRetirementTest: normal play, one-shot retirement, re-entry, failed-close retry passed");
    }
}
