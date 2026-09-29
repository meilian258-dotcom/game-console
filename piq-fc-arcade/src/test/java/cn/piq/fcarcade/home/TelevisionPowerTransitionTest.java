package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelevisionPowerTransitionTest {
    @Test void initialAndReloadedChunkNeverReplayStartup() {
        var animation = new TelevisionPowerTransition();
        animation.observe(true, true, 10); // Initial update tag arrives before onLoad.
        animation.loaded(true);
        assertEquals(1, animation.amount(10));
        animation.unloaded();
        animation.observe(false, true, 11);
        animation.observe(true, true, 12);
        animation.loaded(true);
        assertEquals(1, animation.amount(12));
    }

    @Test void livePowerChangeOpensGraduallyAndSettles() {
        var animation = new TelevisionPowerTransition();
        animation.loaded(false);
        animation.observe(true, true, 100);
        assertEquals(0, animation.amount(100));
        assertEquals(.5, animation.amount(107), .00001);
        assertEquals(1, animation.amount(114));
    }

    @Test void samePowerPacketsDoNotRestartOrChangeProgress() {
        var animation = new TelevisionPowerTransition();
        animation.loaded(false);
        animation.observe(true, true, 100);
        animation.observe(true, true, 105); // Signal/volume/scanline updates.
        assertEquals(.5, animation.amount(107), .00001);
        animation.observe(true, true, 120);
        assertEquals(1, animation.amount(120));
    }

    @Test void rapidToggleContinuesFromCurrentBrightnessAndNewestStateWins() {
        var animation = new TelevisionPowerTransition();
        animation.loaded(false);
        animation.observe(true, true, 100);
        double before = animation.amount(106);
        animation.observe(false, true, 106);
        assertEquals(before, animation.amount(106), .000001);
        double closing = animation.amount(108);
        assertTrue(closing < before);
        animation.observe(true, true, 108);
        assertEquals(closing, animation.amount(108), .000001);
        assertEquals(1, animation.amount(122));
        animation.observe(false, true, 123);
        assertEquals(0, animation.amount(130));
    }

    @Test void disablingAnimationSnapsAndDoesNotReplayWhenEnabled() {
        var animation = new TelevisionPowerTransition();
        animation.loaded(false);
        animation.observe(true, true, 10);
        animation.observe(true, false, 11);
        assertEquals(1, animation.amount(11));
        animation.observe(true, true, 12);
        assertEquals(1, animation.amount(12));
    }

    @Test void outputIsBoundedAndLcdNeverSquashes() {
        for (double amount : new double[]{-1, 0, .001, .2, .5, .9, 1, 3, Double.NaN}) {
            assertEquals(1, TelevisionPowerTransition.verticalScale(false, amount));
            double crt = TelevisionPowerTransition.verticalScale(true, amount);
            assertTrue(crt >= .012 && crt <= 1);
            int brightness = TelevisionPowerTransition.brightness(amount);
            assertTrue(brightness >= 0 && brightness <= 255);
        }
        assertEquals(0, TelevisionPowerTransition.brightness(0));
        assertEquals(255, TelevisionPowerTransition.brightness(1));
    }
}
