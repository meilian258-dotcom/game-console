package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockstepFramePacerTest {
    private static final long FRAME_NANOS = 1_000_000_000L / 60L;

    @Test
    void releasesPacketFramesAtDisplayCadence() {
        LockstepFramePacer pacer = new LockstepFramePacer(FRAME_NANOS);
        long start = 1_000_000_000L;

        assertEquals(1, pacer.framesDue(start, 3));
        assertEquals(0, pacer.framesDue(start + FRAME_NANOS - 1, 2));
        assertEquals(1, pacer.framesDue(start + FRAME_NANOS, 2));
        assertEquals(1, pacer.framesDue(start + FRAME_NANOS * 2, 1));
    }

    @Test
    void catchesUpAfterAStallButKeepsWorkBounded() {
        LockstepFramePacer pacer = new LockstepFramePacer(FRAME_NANOS);
        long start = 2_000_000_000L;
        assertEquals(1, pacer.framesDue(start, 3));

        int catchup = pacer.framesDue(start + FRAME_NANOS * 20, 29);
        assertTrue(catchup > 1);
        assertTrue(catchup <= 12);
    }

    @Test
    void drainsBacklogThatArrivesAfterNetworkStarvation() {
        LockstepFramePacer pacer = new LockstepFramePacer(FRAME_NANOS);
        long start = 3_000_000_000L;

        assertEquals(1, pacer.framesDue(start, 3));
        assertEquals(0, pacer.framesDue(start + FRAME_NANOS * 30, 0));
        assertEquals(12, pacer.framesDue(start + FRAME_NANOS * 30, 30));
        assertEquals(12, pacer.framesDue(start + FRAME_NANOS * 31, 18));
        assertEquals(3, pacer.framesDue(start + FRAME_NANOS * 32, 6));
    }

    @Test
    void resetStartsImmediatelyOnNextPacket() {
        LockstepFramePacer pacer = new LockstepFramePacer(FRAME_NANOS);
        assertEquals(1, pacer.framesDue(10, 1));
        pacer.reset();
        assertEquals(1, pacer.framesDue(20, 1));
    }
}
