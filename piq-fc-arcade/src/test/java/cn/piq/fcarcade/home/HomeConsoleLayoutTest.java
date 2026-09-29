package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeConsoleLayoutTest {
    @Test void consoleAndTwoFrontControllersFitOneBlockInAllFacings() {
        for (int facing = 0; facing < 4; facing++) {
            var b = HomeConsoleLayout.suborBounds(facing);
            assertTrue(b.minX() >= 0 && b.minZ() >= 0 && b.maxX() <= 16 && b.maxZ() <= 16);
            assertEquals(0, b.minY());
            assertTrue(b.maxY() >= (HomeConsoleLayout.CARD_Y * 16 + 7.8 * HomeConsoleLayout.CARD_SCALE));
            assertEquals(b, HomeConsoleLayout.suborBounds(facing+4));
        }
    }
    @Test void realCartridgeFitsSourceSlotWithoutStretching() {
        double cardLeft = 8 + (2.3-8)*HomeConsoleLayout.CARD_SCALE;
        double cardRight = 8 + (13.7-8)*HomeConsoleLayout.CARD_SCALE;
        assertTrue(cardLeft > 8-4.79*.45 && cardRight < 8+4.79*.45);
        double front = HomeConsoleLayout.CARD_Z*16+(7.25-8)*HomeConsoleLayout.CARD_SCALE;
        double back = HomeConsoleLayout.CARD_Z*16+(8.75-8)*HomeConsoleLayout.CARD_SCALE;
        assertTrue(front > 9.15+4.8*.45 && back < 9.15+6.93*.45);
    }
}
