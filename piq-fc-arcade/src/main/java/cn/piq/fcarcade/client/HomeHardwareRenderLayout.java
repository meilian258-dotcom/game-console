package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeHardwareScale;

/** Model-unit anchors from the reviewed ZIP 03/04/05 assets; no game state or I/O. */
public final class HomeHardwareRenderLayout {
    public static final double CARTRIDGE_Y = 4.1 / 16.0;
    public static final double CARTRIDGE_Z = 3.72 / 16.0;
    // FC48: one hollow coaxial video connector; the television end remains three RCA plugs.
    public static final Point CONSOLE_CABLE = new Point(8.0/16,2.28/16,14.97/16);
    public static final Point TV_CABLE = tvPoint(new Point(6.0 / 16, 4.145 / 16, 14.46 / 16));
    public static final int CABLE_SEGMENTS = 24;

    private HomeHardwareRenderLayout() {}

    public record Point(double x, double y, double z) {}
    public record Bounds(Point min, Point max) {}

    /** Original P1 faces east, P2 west; held faces both point toward camera +Z. */
    public static float heldControllerYaw(int port) {
        if (port != 0 && port != 1) throw new IllegalArgumentException("Controller port must be 0 or 1");
        return port == 0 ? -90f : 90f;
    }

    public static Point consolePoint(Point original) {
        var scaled = HomeHardwareScale.consolePoint(original.x() * 16, original.y() * 16, original.z() * 16);
        return new Point(scaled.x() / 16, scaled.y() / 16, scaled.z() / 16);
    }

    public static Point tvPoint(Point original) {
        var scaled = HomeHardwareScale.tvPoint(original.x() * 16, original.y() * 16, original.z() * 16);
        return new Point(scaled.x() / 16, scaled.y() / 16, scaled.z() / 16);
    }

    public static Point insertedCardPoint(Point original) {
        return consolePoint(new Point(original.x(), original.y() + CARTRIDGE_Y, original.z() + CARTRIDGE_Z));
    }

    /** Full eight-block reservation, rotated around the vanilla anchor block center. */
    public static Bounds tvBounds(int turns) {
        return tvBounds(turns, false);
    }

    public static Bounds tvBounds(int turns, boolean centered) {
        Point first = rotate(new Point(centered ? -1 : 0, 0, 0), turns);
        Point opposite = rotate(new Point(2, 2, 2), turns);
        return new Bounds(new Point(Math.min(first.x(), opposite.x()), 0, Math.min(first.z(), opposite.z())),
                new Point(Math.max(first.x(), opposite.x()), 2, Math.max(first.z(), opposite.z())));
    }

    /** Translation of an already-facing-baked TV, not a second rotation. */
    public static Point tvOffset(int turns, boolean centered) {
        if (!centered) return new Point(0, 0, 0);
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Point(0, 0, -0.5);
            case 2 -> new Point(0.5, 0, 0);
            case 3 -> new Point(0, 0, 0.5);
            default -> new Point(-0.5, 0, 0);
        };
    }

    public static Point tvCable(int turns, boolean centered) {
        Point original = rotate(TV_CABLE, turns);
        Point offset = tvOffset(turns, centered);
        return new Point(original.x() + offset.x(), original.y(), original.z() + offset.z());
    }

    /**
     * Minecraft 1.21.1 TextureAtlasSprite#getUOffset/getVOffset use this exact
     * normalized inverse lerp. The result is 0..1, not the legacy 0..16 model UV.
     */
    public static float textureCoordinate(float atlasCoordinate, float spriteMin, float spriteMax) {
        return (atlasCoordinate - spriteMin) / (spriteMax - spriteMin);
    }

    /** Clockwise blockstate rotations north=0, east=1, south=2, west=3. */
    public static Point rotate(Point point, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Point(1 - point.z(), point.y(), point.x());
            case 2 -> new Point(1 - point.x(), point.y(), 1 - point.z());
            case 3 -> new Point(point.z(), point.y(), 1 - point.x());
            default -> point;
        };
    }

    public static Point cablePoint(Point from, Point to, double progress) {
        double t = Math.clamp(progress, 0, 1);
        double distance = Math.sqrt(Math.pow(to.x() - from.x(), 2)
                + Math.pow(to.y() - from.y(), 2) + Math.pow(to.z() - from.z(), 2));
        double sag = Math.min(0.65, 0.12 + distance * 0.065);
        return new Point(from.x() + (to.x() - from.x()) * t,
                from.y() + (to.y() - from.y()) * t - 4 * sag * t * (1 - t),
                from.z() + (to.z() - from.z()) * t);
    }
}
