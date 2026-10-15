package cn.piq.replaycompat.api;

/** World-space vertex, final UV (rotation already applied), packed ARGB tint. */
public record ScreenVertex(double x, double y, double z, float u, float v, int color) {
    public ScreenVertex {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x)>30_000_000 || Math.abs(z)>30_000_000 || Math.abs(y)>4096
                || !Float.isFinite(u) || !Float.isFinite(v) || Math.abs(u)>16 || Math.abs(v)>16)
            throw new IllegalArgumentException("Invalid screen vertex");
    }
}
