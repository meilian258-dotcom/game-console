package com.nokia.mid.ui;

/** Hardware feedback shim. Minecraft has no matching phone light/vibrator. */
public abstract class DeviceControl {
    protected DeviceControl() {
    }

    public static void setLights(int num, int level) {
        // Deliberate no-op.
    }

    public static void flashLights(long duration) {
        // Deliberate no-op.
    }

    public static void startVibra(int frequency, long duration) {
        // Deliberate no-op.
    }

    public static void stopVibra() {
        // Deliberate no-op.
    }
}
