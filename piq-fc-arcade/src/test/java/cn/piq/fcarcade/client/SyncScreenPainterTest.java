package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncScreenPainterTest {
    @Test
    void paintsOpaqueLoadingScreenAndProgressBar() {
        byte[] empty = new byte[NesCore.RGBA_BYTES];
        byte[] half = new byte[NesCore.RGBA_BYTES];
        SyncScreenPainter.paint(empty, 0, 100);
        SyncScreenPainter.paint(half, 50, 100);

        for (int pixel = 3; pixel < half.length; pixel += 4) {
            assertEquals((byte) 0xFF, half[pixel]);
        }
        assertTrue(countWhitePixels(half) > countWhitePixels(empty));
    }

    private static int countWhitePixels(byte[] rgba) {
        int count = 0;
        for (int pixel = 0; pixel < rgba.length; pixel += 4) {
            if ((rgba[pixel] & 0xFF) == 255
                    && (rgba[pixel + 1] & 0xFF) == 255
                    && (rgba[pixel + 2] & 0xFF) == 255) {
                count++;
            }
        }
        return count;
    }
}
