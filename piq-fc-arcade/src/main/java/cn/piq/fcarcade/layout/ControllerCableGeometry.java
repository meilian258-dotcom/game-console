package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Presentation only. Coordinates are block units, never controller/input authority. */
public final class ControllerCableGeometry {
    public enum Style { FAMICOM, SUBOR, SUBOR_WIDE, SFC, SUBOR_COMPACT }
    public static final double PLAYER_RANGE = 6, VIEW_RANGE = 32, RENDER_MARGIN = 9;
    public static final double RADIUS = .006, MAX_LENGTH = 12;
    public static final int SIDES = 8, MAX_SEGMENTS = 64;
    private ControllerCableGeometry() {}

    /** Existing docked mesh's console end; SFC coordinates include its current 1.5 console scale. */
    public static Point socket(Style style, int port, int turns) {
        if (style == null || port < 0 || port > 1) throw new IllegalArgumentException("Controller socket");
        Point point = switch (style) {
            case FAMICOM -> model(port == 0 ? 12.53 : 3.47, 1.59, 14.249);
            case SUBOR -> model(port == 0 ? 15.3125 : .6875, .45, 11.13);
            case SUBOR_WIDE -> model(port == 0 ? 28.22 : 3.78, 2.19, 21.1488);
            case SUBOR_COMPACT -> model(port == 0 ? 28.22 : 3.78, 2.19, cn.piq.fcarcade.home.HomeConsoleLayout.compactZ(21.1488));
            case SFC -> model(port == 0 ? 11.0375 : 4.9625, 1.08375, 2.582375);
        };
        return RocketArcadeGeometry.rotate(point, turns);
    }
    private static Point model(double x, double y, double z) { return new Point(x / 16, y / 16, z / 16); }

    /** Exact identity/held-port gate shared by both adapters. A copied receipt in both hands fails closed. */
    public static int heldHand(UUID expectedPlayer, UUID expectedLease, UUID actualPlayer,
                               int port, UUID mainLease, int mainPort, UUID offLease, int offPort,
                               double distanceSquared, boolean docked) {
        if (docked || port < 0 || port > 1 || expectedPlayer == null || expectedLease == null
                || !expectedPlayer.equals(actualPlayer) || !Double.isFinite(distanceSquared)
                || distanceSquared < 0 || distanceSquared > PLAYER_RANGE * PLAYER_RANGE) return -1;
        boolean main = expectedLease.equals(mainLease) && port == mainPort;
        boolean off = expectedLease.equals(offLease) && port == offPort;
        return main == off ? -1 : main ? 0 : 1;
    }

    /** Camera-local controller top edge. The caller supplies the existing item rig, not a copied pose formula. */
    public static Point firstGrip(double x, double y, double z, double pitch, double yaw) {
        if (!Double.isFinite(x + y + z + pitch + yaw)) return null;
        // Held models are centered at 8/16. The cord meets the upper edge, just inside the shell.
        double p = Math.toRadians(pitch), offset = .14;
        return new Point(x, y + offset * Math.cos(p), z + offset * Math.sin(p));
    }

    /** Stable third-person hand region, relative to interpolated feet. Never reads another player's mutable model. */
    public static Point thirdGrip(double bodyYaw, boolean right, boolean twoHands, boolean crouching) {
        if (!Double.isFinite(bodyYaw)) return null;
        double yaw = Math.toRadians(bodyYaw), side = twoHands ? 0 : (right ? 1 : -1) * .30;
        double forward = twoHands ? .48 : .40;
        return new Point(-Math.sin(yaw) * forward - Math.cos(yaw) * side,
                (crouching ? .94 : 1.17), Math.cos(yaw) * forward - Math.sin(yaw) * side);
    }

    /** Bounded presentation cord. No world queries, force-loading, rope simulation or gameplay reach changes. */
    public static List<Point> cable(Point start, Point end) {
        if (!finite(start) || !finite(end)) return List.of();
        double dx = end.x() - start.x(), dy = end.y() - start.y(), dz = end.z() - start.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length) || length < .001 || length > MAX_LENGTH) return List.of();
        int count = Math.max(8, Math.min(MAX_SEGMENTS, (int) Math.ceil(length * 6)));
        double sag = Math.min(.45, length * .075);
        var points = new ArrayList<Point>(count + 1);
        points.add(start);
        for (int i = 1; i < count; i++) {
            double t = i / (double) count;
            points.add(new Point(start.x() + dx * t, start.y() + dy * t - Math.sin(Math.PI * t) * sag,
                    start.z() + dz * t));
        }
        points.add(end);
        return List.copyOf(points);
    }
    private static boolean finite(Point p) {
        return p != null && Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }
}
