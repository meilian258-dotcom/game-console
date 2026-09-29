package cn.piq.retro.client;

import cn.piq.retro.input.GamepadState;
import cn.piq.retro.input.GamepadState.Control;

/** Pure conversion of GLFW's 15-button / 6-axis standard mapping, testable without native code. */
public final class StandardGamepadState {
    private static final Control[] POSITIONS = {Control.SOUTH, Control.EAST, Control.WEST, Control.NORTH,
            Control.LEFT_BUMPER, Control.RIGHT_BUMPER, Control.SELECT, Control.START, Control.GUIDE,
            Control.LEFT_STICK_CLICK, Control.RIGHT_STICK_CLICK, Control.DPAD_UP, Control.DPAD_RIGHT,
            Control.DPAD_DOWN, Control.DPAD_LEFT};
    private StandardGamepadState() { }
    public static GamepadState decode(GamepadState.DeviceId device, byte[] buttons, float[] axes) {
        if (buttons.length != 15 || axes.length != 6) throw new IllegalArgumentException("Invalid standard gamepad shape");
        long mask = 0;
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] != 0 && buttons[i] != 1) throw new IllegalArgumentException("Invalid button state");
            if (buttons[i] == 1) mask |= POSITIONS[i].mask();
        }
        return new GamepadState(device, true, mask, axes[0], axes[1], axes[2], axes[3], (axes[4] + 1) * .5f, (axes[5] + 1) * .5f);
    }
}
