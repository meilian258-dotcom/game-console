package cn.piq.fcarcade.client;

/** Static display-only shading; never applied to emulator pixels or state. */
public final class CrtScanlinePattern {
    public static final int SOURCE_ROWS = 240;
    public static final double MAX_DARKEN = 0.28;
    public static final double MIN_PROJECTED_HEIGHT = 240.0;
    public static final double FULL_PROJECTED_HEIGHT = 480.0;

    private CrtScanlinePattern() {}

    public static double strength(double projectedHeight) {
        if (!Double.isFinite(projectedHeight)) return 0;
        double blend = Math.clamp((projectedHeight - MIN_PROJECTED_HEIGHT)
                / (FULL_PROJECTED_HEIGHT - MIN_PROJECTED_HEIGHT), 0.0, 1.0);
        // Smooth fade near the sampling limit, rather than a popping on/off edge.
        return MAX_DARKEN * blend * blend * (3.0 - 2.0 * blend);
    }

    public static int brightness(int row, double projectedHeight) {
        requireRow(row);
        return (row & 1) == 0 ? 255 : (int) Math.round(255 * (1 - strength(projectedHeight)));
    }

    public static float top(int row) {
        requireRow(row);
        return row / (float) SOURCE_ROWS;
    }

    public static float bottom(int row) {
        requireRow(row);
        return (row + 1) / (float) SOURCE_ROWS;
    }

    private static void requireRow(int row) {
        if (row < 0 || row >= SOURCE_ROWS) throw new IllegalArgumentException("scanline row");
    }
}
