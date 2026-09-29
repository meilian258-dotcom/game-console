package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrtScanlinePatternTest {
    @Test void fullCoverageHasNoGapsAndStaticPhase() {
        assertEquals(0F, CrtScanlinePattern.top(0));
        assertEquals(1F, CrtScanlinePattern.bottom(239));
        for (int row = 0; row < 240; row++) {
            assertTrue(CrtScanlinePattern.bottom(row) > CrtScanlinePattern.top(row));
            if (row > 0) assertEquals(CrtScanlinePattern.bottom(row - 1), CrtScanlinePattern.top(row));
            assertEquals((row & 1) == 0 ? 255 : 184, CrtScanlinePattern.brightness(row, 600));
        }
    }

    @Test void distantAndInvalidProjectionAreUnchanged() {
        for (double height : new double[]{-1, 0, 120, 239, 240, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(0, CrtScanlinePattern.strength(height));
            assertEquals(255, CrtScanlinePattern.brightness(1, height));
        }
    }

    @Test void shadingIsBoundedAndSmoothlyIncreases() {
        double previous = 0;
        for (int height = 0; height <= 1000; height++) {
            double current = CrtScanlinePattern.strength(height);
            assertTrue(current >= previous && current <= 0.28);
            previous = current;
            assertTrue(CrtScanlinePattern.brightness(1, height) >= 184);
        }
        assertEquals(0.14, CrtScanlinePattern.strength(360), 1e-9);
        assertEquals(0.28, CrtScanlinePattern.strength(480));
    }

    @Test void invalidRowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CrtScanlinePattern.top(-1));
        assertThrows(IllegalArgumentException.class, () -> CrtScanlinePattern.bottom(240));
        assertThrows(IllegalArgumentException.class, () -> CrtScanlinePattern.brightness(240, 600));
    }
}
