package cn.piq.retro.input;

import java.util.Objects;

/** A platform-neutral snapshot. Stick +X points right, +Y down; triggers are 0..1. */
public record GamepadState(DeviceId device, boolean connected, long buttons,
                           float leftX, float leftY, float rightX, float rightY,
                           float leftTrigger, float rightTrigger) {
    /** Include both the device/slot identity and a new generation after every reconnect. */
    public record DeviceId(String id, long generation) {
        public DeviceId {
            Objects.requireNonNull(id, "id");
            if (id.isBlank() || id.length() > 256 || generation < 0) {
                throw new IllegalArgumentException("Invalid device identity");
            }
        }
    }

    /** Names describe positions, never manufacturer-specific A/B/X/Y lettering. */
    public enum Control {
        SOUTH, EAST, WEST, NORTH, LEFT_BUMPER, RIGHT_BUMPER, SELECT, START, GUIDE,
        LEFT_STICK_CLICK, RIGHT_STICK_CLICK, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
        LEFT_TRIGGER, RIGHT_TRIGGER,
        LEFT_STICK_UP, LEFT_STICK_DOWN, LEFT_STICK_LEFT, LEFT_STICK_RIGHT,
        RIGHT_STICK_UP, RIGHT_STICK_DOWN, RIGHT_STICK_LEFT, RIGHT_STICK_RIGHT;

        public long mask() { return 1L << ordinal(); }
        public boolean physicalButton() { return ordinal() <= DPAD_RIGHT.ordinal(); }
    }

    public static final long PHYSICAL_BUTTON_MASK = (1L << (Control.DPAD_RIGHT.ordinal() + 1)) - 1;
    public static final long ALL_CONTROL_MASK = (1L << Control.values().length) - 1;

    public GamepadState {
        Objects.requireNonNull(device, "device");
        if ((buttons & ~PHYSICAL_BUTTON_MASK) != 0) throw new IllegalArgumentException("Raw buttons contain virtual controls");
        checkRange(leftX, -1, 1); checkRange(leftY, -1, 1);
        checkRange(rightX, -1, 1); checkRange(rightY, -1, 1);
        checkRange(leftTrigger, 0, 1); checkRange(rightTrigger, 0, 1);
        if (!connected) {
            buttons = 0; leftX = 0; leftY = 0; rightX = 0; rightY = 0;
            leftTrigger = 0; rightTrigger = 0;
        }
    }

    public static GamepadState neutral(DeviceId device) {
        return new GamepadState(device, true, 0, 0, 0, 0, 0, 0, 0);
    }

    public static GamepadState disconnected(DeviceId device) {
        return new GamepadState(device, false, 0, 0, 0, 0, 0, 0, 0);
    }

    public static long buttons(Control... controls) {
        long result = 0;
        for (Control control : controls) {
            if (!Objects.requireNonNull(control).physicalButton()) throw new IllegalArgumentException("Not a physical button");
            result |= control.mask();
        }
        return result;
    }

    static void checkRange(float value, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid analog value");
    }
}
