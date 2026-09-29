package cn.piq.retro.input;

/** Radial Schmitt deadzone plus angular hysteresis for stable eight-way digital directions. */
public final class StickDeadzone {
    public static final float DEFAULT_ENTER = 0.25f, DEFAULT_EXIT = 0.18f;
    private static final double DIRECTION_ENTER = Math.sin(Math.toRadians(22.5));
    private static final double DIRECTION_EXIT = Math.sin(Math.toRadians(17.5));
    private final float enter, exit;
    private boolean active, up, down, left, right;

    public record Sample(float x, float y, boolean active, boolean up, boolean down, boolean left, boolean right) { }

    public StickDeadzone() { this(DEFAULT_ENTER, DEFAULT_EXIT); }

    public StickDeadzone(float enter, float exit) {
        GamepadState.checkRange(enter, 0, 1); GamepadState.checkRange(exit, 0, 1);
        if (enter <= exit || enter >= 1) throw new IllegalArgumentException("Require 0 <= exit < enter < 1");
        this.enter = enter; this.exit = exit;
    }

    public Sample sample(float x, float y) {
        double radius = radius(x, y);
        active = active ? radius > exit : radius >= enter;
        if (!active) {
            reset();
            return new Sample(0, 0, false, false, false, false, false);
        }
        double unitX = x / radius, unitY = y / radius;
        left = direction(-unitX, left); right = direction(unitX, right);
        up = direction(-unitY, up); down = direction(unitY, down);
        double magnitude = (Math.min(radius, 1) - exit) / (1 - exit);
        return new Sample((float) (unitX * magnitude), (float) (unitY * magnitude), true, up, down, left, right);
    }

    public boolean centered(float x, float y) { return radius(x, y) <= exit; }
    public void reset() { active = false; up = false; down = false; left = false; right = false; }

    private static boolean direction(double component, boolean wasDown) {
        return component >= (wasDown ? DIRECTION_EXIT : DIRECTION_ENTER);
    }

    private static double radius(float x, float y) {
        GamepadState.checkRange(x, -1, 1); GamepadState.checkRange(y, -1, 1);
        return Math.hypot(x, y);
    }

    /** Independent trigger Schmitt latch. Source must translate released trigger to zero. */
    public static final class Trigger {
        public static final float DEFAULT_ENTER = 0.55f, DEFAULT_EXIT = 0.35f;
        private final float enter, exit;
        private boolean active;
        public Trigger() { this(DEFAULT_ENTER, DEFAULT_EXIT); }
        public Trigger(float enter, float exit) {
            GamepadState.checkRange(enter, 0, 1); GamepadState.checkRange(exit, 0, 1);
            if (enter <= exit) throw new IllegalArgumentException("Require exit < enter");
            this.enter = enter; this.exit = exit;
        }
        public boolean sample(float value) {
            GamepadState.checkRange(value, 0, 1);
            active = active ? value > exit : value >= enter;
            return active;
        }
        public boolean released(float value) { GamepadState.checkRange(value, 0, 1); return value <= exit; }
        public void reset() { active = false; }
    }
}
