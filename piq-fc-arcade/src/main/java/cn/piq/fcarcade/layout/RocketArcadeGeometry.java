package cn.piq.fcarcade.layout;

import java.util.List;

/**
 * Geometry for the supplied 02_单人火箭车街机/arcade_side_fit.json.
 * Values exposed here are in blocks; the source model uses sixteenths.
 * North is the unrotated model, and positive quarter turns follow blockstates.
 * No Minecraft/render classes are used, so all consumers share testable data.
 */
public final class RocketArcadeGeometry {
    public static final double MODEL_TOP = 32.0D / 16.0D;
    public static final double SCREEN_TILT_DEGREES = 22.5D;
    public static final double SCREEN_SURFACE_OFFSET = 0.001D;
    public static final double LEADERBOARD_SURFACE_OFFSET = 0.01D;
    public static final int LEADERBOARD_LINE_WIDTH = 160;
    public static final float LEADERBOARD_SCALE = 0.15F;
    public static final double LEADERBOARD_BASELINE_BELOW_CENTER = 0.075D;

    private static final double TILT = Math.toRadians(SCREEN_TILT_DEGREES);
    private static final double SIN = Math.sin(TILT);
    private static final double COS = Math.cos(TILT);
    private static final Point NORTH_NORMAL = new Point(0.0D, SIN, -COS);
    private static final ScreenQuad NORTH_SCREEN = createNorthScreen(SCREEN_SURFACE_OFFSET);
    private static final Point NORTH_LEADERBOARD_CENTER =
            createNorthScreen(LEADERBOARD_SURFACE_OFFSET).center();
    private static final List<ScreenQuad> SCREENS = List.of(
            NORTH_SCREEN,
            rotateScreen(NORTH_SCREEN, 1),
            rotateScreen(NORTH_SCREEN, 2),
            rotateScreen(NORTH_SCREEN, 3));

    // One overall outline avoids drawing every internal collision step.
    // Keep this independent from the stepped physical collision below.
    private static final Box NORTH_SELECTION =
            modelBox(0.68, 0.0, 0.3820101013, 15.32, 32.0, 14.65);
    private static final List<Box> SELECTIONS = List.of(
            NORTH_SELECTION,
            rotateBoxes(List.of(NORTH_SELECTION), 1).getFirst(),
            rotateBoxes(List.of(NORTH_SELECTION), 2).getFirst(),
            rotateBoxes(List.of(NORTH_SELECTION), 3).getFirst());

    // A small, conservative stepped cabinet silhouette, not 155 decorative
    // collision cubes. Buttons and bevels do not create tiny snagging edges.
    private static final List<Box> NORTH_COLLISION = List.of(
            modelBox(0.68, 0.00, 2.50, 15.32, 13.65, 14.65),
            modelBox(0.68, 13.65, 0.382, 15.32, 16.50, 14.65),
            modelBox(0.68, 16.50, 3.75, 15.32, 19.50, 14.55),
            modelBox(0.68, 19.50, 4.80, 15.32, 22.50, 14.55),
            modelBox(0.68, 22.50, 6.00, 15.32, 25.76, 14.55),
            modelBox(0.68, 25.76, 2.50, 15.32, 28.25, 14.55),
            modelBox(0.68, 28.25, 2.72, 15.32, 32.00, 14.65),
            modelBox(9.90, 16.50, 2.20, 11.30, 18.15, 3.70));
    private static final List<List<Box>> COLLISIONS = List.of(
            NORTH_COLLISION,
            rotateBoxes(NORTH_COLLISION, 1),
            rotateBoxes(NORTH_COLLISION, 2),
            rotateBoxes(NORTH_COLLISION, 3));

    private RocketArcadeGeometry() {
    }

    public static ScreenQuad screen(int clockwiseQuarterTurns) {
        return SCREENS.get(Math.floorMod(clockwiseQuarterTurns, 4));
    }

    public static Point leaderboardCenter(int clockwiseQuarterTurns) {
        return rotate(NORTH_LEADERBOARD_CENTER, clockwiseQuarterTurns);
    }

    public static Point leaderboardTextOrigin(int clockwiseQuarterTurns) {
        // Vanilla text displays are bottom-anchored. Leave room above the
        // origin for the title and three names, including wrapped long names.
        return rotate(new Point(NORTH_LEADERBOARD_CENTER.x(),
                NORTH_LEADERBOARD_CENTER.y() - COS * LEADERBOARD_BASELINE_BELOW_CENTER,
                NORTH_LEADERBOARD_CENTER.z() - SIN * LEADERBOARD_BASELINE_BELOW_CENTER),
                clockwiseQuarterTurns);
    }

    public static List<Box> collisionBoxes(int clockwiseQuarterTurns) {
        return COLLISIONS.get(Math.floorMod(clockwiseQuarterTurns, 4));
    }

    public static Box selectionBox(int clockwiseQuarterTurns) {
        return SELECTIONS.get(Math.floorMod(clockwiseQuarterTurns, 4));
    }

    public static int quarterTurns(int directionX, int directionZ) {
        if (directionX == 0 && directionZ == -1) return 0;
        if (directionX == 1 && directionZ == 0) return 1;
        if (directionX == 0 && directionZ == 1) return 2;
        if (directionX == -1 && directionZ == 0) return 3;
        throw new IllegalArgumentException("Rocket cabinet requires a horizontal facing");
    }

    public static Point rotate(Point point, int clockwiseQuarterTurns) {
        return switch (Math.floorMod(clockwiseQuarterTurns, 4)) {
            case 0 -> point;
            case 1 -> new Point(1.0D - point.z(), point.y(), point.x());
            case 2 -> new Point(1.0D - point.x(), point.y(), 1.0D - point.z());
            case 3 -> new Point(point.z(), point.y(), 1.0D - point.x());
            default -> throw new AssertionError();
        };
    }

    private static ScreenQuad createNorthScreen(double offset) {
        // The visible north face is z=4.6, rotated around [8,17.8,4.8].
        // 10.52 / 7.89 == 4/3 on the actual sloping surface, not its Y projection.
        return new ScreenQuad(
                screenPoint(2.74, 18.74, offset),
                screenPoint(13.26, 18.74, offset),
                screenPoint(13.26, 26.63, offset),
                screenPoint(2.74, 26.63, offset),
                NORTH_NORMAL);
    }

    private static Point screenPoint(double x, double y, double offset) {
        double dy = y - 17.8D;
        double dz = 4.6D - 4.8D;
        return new Point(x / 16.0D,
                (17.8D + dy * COS - dz * SIN) / 16.0D + NORTH_NORMAL.y() * offset,
                (4.8D + dy * SIN + dz * COS) / 16.0D + NORTH_NORMAL.z() * offset);
    }

    private static ScreenQuad rotateScreen(ScreenQuad screen, int turns) {
        // Rotate a direction about the origin, unlike points about block center.
        Point normalEnd = rotate(new Point(0.5D + screen.normal().x(),
                screen.normal().y(), 0.5D + screen.normal().z()), turns);
        Point normal = new Point(normalEnd.x() - 0.5D,
                normalEnd.y(), normalEnd.z() - 0.5D);
        return new ScreenQuad(rotate(screen.lowerMinX(), turns),
                rotate(screen.lowerMaxX(), turns), rotate(screen.upperMaxX(), turns),
                rotate(screen.upperMinX(), turns), normal);
    }

    private static Box modelBox(double x1, double y1, double z1,
                                double x2, double y2, double z2) {
        return new Box(x1 / 16.0D, y1 / 16.0D, z1 / 16.0D,
                x2 / 16.0D, y2 / 16.0D, z2 / 16.0D);
    }

    private static List<Box> rotateBoxes(List<Box> boxes, int turns) {
        return boxes.stream().map(box -> {
            Point a = rotate(new Point(box.minX(), box.minY(), box.minZ()), turns);
            Point b = rotate(new Point(box.maxX(), box.maxY(), box.maxZ()), turns);
            return new Box(Math.min(a.x(), b.x()), a.y(), Math.min(a.z(), b.z()),
                    Math.max(a.x(), b.x()), b.y(), Math.max(a.z(), b.z()));
        }).toList();
    }

    public record Point(double x, double y, double z) {
        public double distanceTo(Point other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public record ScreenQuad(Point lowerMinX, Point lowerMaxX, Point upperMaxX,
                             Point upperMinX, Point normal) {
        public double width() { return lowerMinX.distanceTo(lowerMaxX); }
        public double height() { return lowerMinX.distanceTo(upperMinX); }
        public double aspectRatio() { return width() / height(); }
        public Point center() {
            return new Point((lowerMinX.x() + upperMaxX.x()) / 2.0D,
                    (lowerMinX.y() + upperMaxX.y()) / 2.0D,
                    (lowerMinX.z() + upperMaxX.z()) / 2.0D);
        }
    }

    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {
        public boolean contains(Point point) {
            return point.x() >= minX && point.x() <= maxX
                    && point.y() >= minY && point.y() <= maxY
                    && point.z() >= minZ && point.z() <= maxZ;
        }
        public double volume() { return (maxX - minX) * (maxY - minY) * (maxZ - minZ); }
    }
}
