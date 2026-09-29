package cn.piq.fcarcade.home;

/** SB926 source-mesh coordinates after the reviewed uniform 0.45 world transform. */
public final class HomeConsoleLayout {
    private HomeConsoleLayout() {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public static final double CARD_SCALE = 0.30;
    public static final double CARD_X = 8.0 / 16, CARD_Y = 0.81 / 16, CARD_Z = 11.7375 / 16;
    public static final double AV_X = 12.77 / 16, AV_Y = 0.50175 / 16, AV_Z = 12.68 / 16;
    // Reviewed v5: chassis X/Z reduced to 80%; original-sized card and controller remain separate.
    public static final double WIDE_CARD_X = 16.0 / 16, WIDE_CARD_Y = 1.52 / 16, WIDE_CARD_Z = 22.164 / 16;
    public static final double WIDE_CARD_SCALE = .60;
    public static final double WIDE_AV_X = 24.32 / 16, WIDE_AV_Y = 1.05 / 16, WIDE_AV_Z = 23.632 / 16;
    public static final double WIDE_AV_SPACING = 1.6 / 16;

    /** New two-cell chassis. Old saved wide devices retain the original four-cell geometry. */
    public static double compactZ(double units) { return units * .8 - 4; }
    public static double suborZ(double blocks, boolean compact) { return compact ? compactZ(blocks * 16) / 16 : blocks; }

    public static Bounds suborBounds(int turns, boolean wide, boolean compact) {
        if (!wide || !compact) return suborBounds(turns, wide);
        double minX=3.2,maxX=28.8,minZ=compactZ(6.5),maxZ=compactZ(23.8);
        return switch (Math.floorMod(turns,4)) {
            case 1 -> new Bounds(16-maxZ,0,minX,16-minZ,6.3,maxX);
            case 2 -> new Bounds(16-maxX,0,16-maxZ,16-minX,6.3,16-minZ);
            case 3 -> new Bounds(minZ,0,16-maxX,maxZ,6.3,16-minX);
            default -> new Bounds(minX,0,minZ,maxX,6.3,maxZ);
        };
    }

    public static Bounds suborBounds(int turns, boolean wide) {
        if (!wide) return suborBounds(turns);
        double minX = 3.2, maxX = 28.8, minZ = 6.5, maxZ = 23.8;
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Bounds(16-maxZ, 0, minX, 16-minZ, 6.3, maxX);
            case 2 -> new Bounds(16-maxX, 0, 16-maxZ, 16-minX, 6.3, 16-minZ);
            case 3 -> new Bounds(minZ, 0, 16-maxX, maxZ, 6.3, 16-minX);
            default -> new Bounds(minX, 0, minZ, maxX, 6.3, maxZ);
        };
    }

    /** Includes the removable cartridge; model units, not blocks. Old FC geometry is untouched. */
    public static Bounds suborBounds(int turns) {
        double minX = .30, maxX = 15.70, minZ = 3.34, maxZ = 12.68;
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Bounds(16-maxZ, 0, minX, 16-minZ, 3.2, maxX);
            case 2 -> new Bounds(16-maxX, 0, 16-maxZ, 16-minX, 3.2, 16-minZ);
            case 3 -> new Bounds(minZ, 0, 16-maxX, maxZ, 3.2, 16-minX);
            default -> new Bounds(minX, 0, minZ, maxX, 3.2, maxZ);
        };
    }
}
