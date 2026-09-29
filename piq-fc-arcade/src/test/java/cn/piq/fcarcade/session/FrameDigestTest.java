package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FrameDigestTest {
    @Test
    void producesStableDigestAndDetectsPixelChanges() {
        byte[] frame = {0, 1, 2, (byte) 0xFF};
        long first = FrameDigest.calculate(frame);

        assertEquals(first, FrameDigest.calculate(frame.clone()));
        frame[2] = 3;
        assertNotEquals(first, FrameDigest.calculate(frame));
    }
}
