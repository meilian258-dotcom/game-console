package cn.piq.fcarcade.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArcadeGlobalSettingsTest {
    @Test
    void validatesRangesAndUsesTheLargerTrackingDistance() {
        ArcadeGlobalSettings settings = new ArcadeGlobalSettings(12, 24, 75, 30);

        assertEquals(576.0D, settings.trackingDistanceSquared());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArcadeGlobalSettings(3, 16, 100, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArcadeGlobalSettings(16, 65, 100, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArcadeGlobalSettings(16, 16, 101, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArcadeGlobalSettings(16, 16, 100, 3651));
    }
}
