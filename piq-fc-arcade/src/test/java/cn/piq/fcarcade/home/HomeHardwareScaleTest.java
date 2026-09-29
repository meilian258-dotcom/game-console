package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeHardwareScaleTest {
    @Test void consoleKeepsBaseAndCentreWhileShrinkingControllersAndCartridge() {
        assertEquals(new HomeHardwareScale.Point(8, 0, 8), HomeHardwareScale.consolePoint(8, 0, 8));
        var min = HomeHardwareScale.consolePoint(-1.12, 0.18, -3.51);
        var max = HomeHardwareScale.consolePoint(17.12, 11.90001, 20.24082);
        assertEquals(2.528, min.x(), 1e-9);
        assertEquals(1.094, min.z(), 1e-9);
        assertEquals(13.472, max.x(), 1e-9);
        assertEquals(7.140006, max.y(), 1e-9);
        assertTrue(min.x() >= 0 && min.z() >= 0 && max.x() <= 16 && max.z() <= 16);
    }

    @Test void tvScalesAboutOriginNotAnchorCentre() {
        assertEquals(new HomeHardwareScale.Point(0, 0, 0), HomeHardwareScale.tvPoint(0, 0, 0));
        assertEquals(new HomeHardwareScale.Point(32, 25.4, 28.91), HomeHardwareScale.tvPoint(16, 12.7, 14.455));
    }
}
