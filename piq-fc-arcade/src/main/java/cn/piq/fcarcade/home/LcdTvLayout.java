package cn.piq.fcarcade.home;

/** Thin one-cell LCD with its desktop stand; bounds in 16-units-per-block coordinates. */
public final class LcdTvLayout {
    private LcdTvLayout() {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public static Bounds bounds(int turns) {
        return Math.floorMod(turns, 2) == 0 ? new Bounds(0, 0, 4.8, 16, 13.5, 11.2)
                : new Bounds(4.8, 0, 0, 11.2, 13.5, 16);
    }
}
