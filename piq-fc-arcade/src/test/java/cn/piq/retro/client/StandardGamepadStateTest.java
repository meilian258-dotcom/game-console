package cn.piq.retro.client;

import cn.piq.retro.input.GamepadState;
import cn.piq.retro.input.GamepadState.Control;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandardGamepadStateTest {
    private static final GamepadState.DeviceId PAD = new GamepadState.DeviceId("0:guid", 1);
    @Test void everyGlfwButtonUsesCorrectPhysicalPositionIncludingClockwiseDpadOrder() {
        Control[] expected = {Control.SOUTH, Control.EAST, Control.WEST, Control.NORTH, Control.LEFT_BUMPER, Control.RIGHT_BUMPER,
                Control.SELECT, Control.START, Control.GUIDE, Control.LEFT_STICK_CLICK, Control.RIGHT_STICK_CLICK,
                Control.DPAD_UP, Control.DPAD_RIGHT, Control.DPAD_DOWN, Control.DPAD_LEFT};
        for (int i = 0; i < expected.length; i++) {
            byte[] buttons = new byte[15]; buttons[i] = 1;
            var sample = StandardGamepadState.decode(PAD, buttons, new float[]{0, 0, 0, 0, -1, -1});
            assertEquals(expected[i].mask(), sample.buttons());
        }
    }
    @Test void axesKeepOrientationAndTriggersTranslateFromMinusOneToZero() {
        var sample = StandardGamepadState.decode(PAD, new byte[15], new float[]{1, -1, -.5f, .5f, -1, 1});
        assertEquals(1, sample.leftX()); assertEquals(-1, sample.leftY());
        assertEquals(-.5f, sample.rightX()); assertEquals(.5f, sample.rightY());
        assertEquals(0, sample.leftTrigger()); assertEquals(1, sample.rightTrigger());
    }
    @Test void malformedShapeButtonOrAxesCannotEnterDomainState() {
        assertThrows(IllegalArgumentException.class, () -> StandardGamepadState.decode(PAD, new byte[14], new float[6]));
        byte[] invalid = new byte[15]; invalid[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> StandardGamepadState.decode(PAD, invalid, new float[6]));
        assertThrows(IllegalArgumentException.class, () -> StandardGamepadState.decode(PAD, new byte[15], new float[]{0, 0, 0, 0, Float.NaN, -1}));
    }
}
