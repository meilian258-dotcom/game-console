package cn.piq.fcarcade.home;

/** Shared model-unit (16 units/block) transforms; independent of client classes. */
public final class HomeHardwareScale {
    public static final double CONSOLE_SCALE = 0.6;
    public static final double TV_SCALE = 2.0;

    private HomeHardwareScale() {}

    public record Point(double x, double y, double z) {}

    public static Point consolePoint(double x, double y, double z) {
        return new Point(8 + (x - 8) * CONSOLE_SCALE, y * CONSOLE_SCALE,
                8 + (z - 8) * CONSOLE_SCALE);
    }

    public static Point tvPoint(double x, double y, double z) {
        return new Point(x * TV_SCALE, y * TV_SCALE, z * TV_SCALE);
    }
}
