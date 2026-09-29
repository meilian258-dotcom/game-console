package cn.piq.fcarcade.layout;

import java.util.List;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Box;

/** User-authored 24x32x17.6 cabinet, centred in the existing two-wide reservation.
 * All world rotations use the anchor block's (.5,0,.5), not the 2x2 center. */
public final class DualCabinetGeometry {
    public static final float MODEL_SCALE = 1F;
    public static final double MODEL_TOP = 2;
    public static final double MODEL_X_OFFSET = .25;
    public static final double MODEL_Y_OFFSET = 0;
    // The unchanged two-deep legacy reservation ends at z=2. Move the whole
    // body, glass and controls together so a wall in the next row meets its back.
    public static final double SOURCE_FRONT = .38201010126776774;
    public static final double BODY_DEPTH = 17.6;
    public static final double MODEL_Z_OFFSET = (32 - SOURCE_FRONT - BODY_DEPTH) / 16;
    public static final double SCREEN_OFFSET = .0015;
    public static final float LEADERBOARD_SCALE = .16F;
    private static final Point NORTH_NORMAL = new Point(0, .3826834323650898, -.9238795325112867);
    private static final ScreenQuad NORTH = north(SCREEN_OFFSET);
    private static final List<ScreenQuad> SCREENS = List.of(NORTH, rotate(NORTH, 1), rotate(NORTH, 2), rotate(NORTH, 3));
    private static final ScreenQuad COMPACT_NORTH = north(SCREEN_OFFSET, true);
    private static final List<ScreenQuad> COMPACT_SCREENS = List.of(COMPACT_NORTH, rotate(COMPACT_NORTH, 1), rotate(COMPACT_NORTH, 2), rotate(COMPACT_NORTH, 3));
    private DualCabinetGeometry() {}
    /** Legacy public signatures retain FC58 placement; only an instance's compact flag opts in. */
    public static double modelZOffset(boolean compact) { return MODEL_Z_OFFSET - (compact ? 1 : 0); }
    public static ScreenQuad screen(int turns) { return screen(turns, false); }
    public static ScreenQuad screen(int turns, boolean compact) { return (compact ? COMPACT_SCREENS : SCREENS).get(Math.floorMod(turns, 4)); }
    public static Point occupancy(int turns) {
        return occupancy(turns, false);
    }
    public static Point occupancy(int turns, boolean compact) {
        return RocketArcadeGeometry.rotate(new Point(1, MODEL_TOP + .25, .5 + modelZOffset(compact)), turns);
    }
    public static Point leaderboardTextOrigin(int turns) {
        return leaderboardTextOrigin(turns, false);
    }
    public static Point leaderboardTextOrigin(int turns, boolean compact) {
        var center = north(.012, compact).center();
        return RocketArcadeGeometry.rotate(new Point(center.x(),
                center.y() - .1125 * Math.cos(Math.PI / 8),
                center.z() - .1125 * Math.sin(Math.PI / 8)), turns);
    }
    public static Box bounds(int turns) {
        return bounds(turns, false);
    }
    /** Visual bounds include the 1.6px front overhang; world collision clips to its owned cells. */
    public static Box bounds(int turns, boolean compact) {
        double back = compact ? 1 : 2;
        var a = RocketArcadeGeometry.rotate(new Point(.25, 0, back - BODY_DEPTH / 16), turns);
        var b = RocketArcadeGeometry.rotate(new Point(1.75, MODEL_TOP, back), turns);
        return new Box(Math.min(a.x(), b.x()), 0, Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), MODEL_TOP, Math.max(a.z(), b.z()));
    }
    private static ScreenQuad north(double offset) {
        return north(offset, false);
    }
    private static ScreenQuad north(double offset, boolean compact) {
        // Exact north face of source cube 808ee99f-a604-41e7-928d-ff7286a7d1fd.
        return new ScreenQuad(point(3,14.55,4.6,offset,compact), point(21,14.55,4.6,offset,compact),
                point(21,28.05,4.6,offset,compact), point(3,28.05,4.6,offset,compact), NORTH_NORMAL);
    }
    private static Point point(double x, double y, double z, double offset, boolean compact) {
        double dy=y-14,dz=z-4.8,c=Math.cos(Math.PI/8),s=Math.sin(Math.PI/8);
        return new Point(x / 16 + MODEL_X_OFFSET, (14+dy*c-dz*s) / 16 + NORTH_NORMAL.y() * offset,
                (4.8+dy*s+dz*c) / 16 + modelZOffset(compact) + NORTH_NORMAL.z() * offset);
    }
    private static ScreenQuad rotate(ScreenQuad q, int turns) {
        Point end = RocketArcadeGeometry.rotate(new Point(.5, NORTH_NORMAL.y(), .5 + NORTH_NORMAL.z()), turns);
        return new ScreenQuad(RocketArcadeGeometry.rotate(q.lowerMinX(), turns),
                RocketArcadeGeometry.rotate(q.lowerMaxX(), turns), RocketArcadeGeometry.rotate(q.upperMaxX(), turns),
                RocketArcadeGeometry.rotate(q.upperMinX(), turns), new Point(end.x() - .5, end.y(), end.z() - .5));
    }
}
