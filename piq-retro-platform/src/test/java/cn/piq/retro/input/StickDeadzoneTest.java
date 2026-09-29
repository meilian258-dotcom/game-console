package cn.piq.retro.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StickDeadzoneTest {
    @Test void radialEnterAndExitHaveDifferentThresholds() {
        var filter = new StickDeadzone();
        assertFalse(filter.sample(.24f, 0).active());
        assertTrue(filter.sample(.25f, 0).active());
        assertTrue(filter.sample(.20f, 0).active());
        assertFalse(filter.sample(.18f, 0).active());
        assertFalse(filter.sample(.20f, 0).active());
        assertTrue(filter.centered(.18f, 0));
        assertFalse(filter.centered(.13f, .13f));
    }

    @Test void diagonalUsesRadiusRatherThanSeparateSquareDeadzones() {
        var filter = new StickDeadzone();
        var sample = filter.sample(.18f, -.18f);
        assertTrue(sample.active()); assertTrue(sample.right()); assertTrue(sample.up());
        assertFalse(sample.left()); assertFalse(sample.down());
        assertTrue(sample.x() > 0); assertTrue(sample.y() < 0);
        sample = filter.sample(1, 1);
        assertEquals(1, Math.hypot(sample.x(), sample.y()), .000001);
    }

    @Test void angularHysteresisDoesNotFlickerNearEightWaySectorBoundaries() {
        var filter = new StickDeadzone();
        assertFalse(atDegrees(filter, 20).down());
        assertTrue(atDegrees(filter, 25).down());
        assertTrue(atDegrees(filter, 20).down());
        assertFalse(atDegrees(filter, 15).down());
        assertFalse(atDegrees(filter, 20).down());
        var reversed = filter.sample(-1, 0);
        assertTrue(reversed.left()); assertFalse(reversed.right());
        assertFalse(reversed.down()); assertFalse(reversed.up());
    }

    @Test void resetAndJitterCannotLeakLatchedDirections() {
        var filter = new StickDeadzone();
        filter.sample(0, -1);
        filter.reset();
        assertFalse(filter.sample(0, -.20f).up());
        for (int i = 0; i < 1000; i++) {
            var sample = filter.sample((i % 3 - 1) * .05f, (i % 5 - 2) * .05f);
            assertFalse(sample.active());
        }
    }

    @Test void triggerHysteresisAndResetAreIndependent() {
        var filter = new StickDeadzone.Trigger();
        assertFalse(filter.sample(.54f));
        assertTrue(filter.sample(.55f));
        assertTrue(filter.sample(.4f));
        assertFalse(filter.sample(.35f));
        assertFalse(filter.sample(.4f));
        assertTrue(filter.sample(1));
        filter.reset();
        assertFalse(filter.sample(.4f));
        assertTrue(filter.released(.35f));
        assertFalse(filter.released(.36f));
    }

    @Test void invalidThresholdsAndAnalogValuesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone(.1f, .2f));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone(1, .2f));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone(Float.NaN, .2f));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone().sample(Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone().centered(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone.Trigger(.2f, .2f));
        assertThrows(IllegalArgumentException.class, () -> new StickDeadzone.Trigger().sample(-1));
    }

    private static StickDeadzone.Sample atDegrees(StickDeadzone filter, double degrees) {
        return filter.sample((float) Math.cos(Math.toRadians(degrees)), (float) Math.sin(Math.toRadians(degrees)));
    }
}
