package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.Optional;

/** Pure optical aim coordinates, not a light sensor or a game hit decision.
 * Caller supplies the actual displayed image quad, never its black border/glass.
 * Eye and direction are anchor-local. Distance is in blocks; direction need not be unit length.
 * Existing renderer UVs are upperMaxX=(0,0), upperMinX=(1,0).
 */
public final class ScreenRayMapping {
    private ScreenRayMapping() {}
    public record Pixel(int x, int y, double u, double v, double distance) {}

    public static Optional<Pixel> hit(ScreenSurfaceGeometry.Surface surface, Point eye,
                                       Point direction, double maxDistance) {
        if (surface == null || !finite(eye) || !finite(surface.translation())) return Optional.empty();
        return hit(surface.image(), minus(eye, surface.translation()), direction, maxDistance);
    }

    public static Optional<Pixel> hit(ScreenQuad image, Point eye, Point direction, double maxDistance) {
        return hit(image, eye, direction, maxDistance, 256, 240);
    }

    public static Optional<Pixel> hit(ScreenQuad image, Point eye, Point direction,
                                       double maxDistance, int width, int height) {
        if (image == null || !finite(eye) || !finite(direction) || !Double.isFinite(maxDistance)
                || maxDistance <= 0 || width < 1 || height < 1 || width > 16384 || height > 16384
                || !finite(image.upperMaxX()) || !finite(image.upperMinX())
                || !finite(image.lowerMaxX()) || !finite(image.lowerMinX()) || !finite(image.normal()))
            return Optional.empty();
        double length = Math.sqrt(dot(direction, direction));
        double normalLength = Math.sqrt(dot(image.normal(), image.normal()));
        if (!Double.isFinite(length) || length < 1e-12 || !Double.isFinite(normalLength)
                || normalLength < 1e-12) return Optional.empty();
        Point ray = scale(direction, 1 / length), normal = scale(image.normal(), 1 / normalLength);
        double denominator = dot(ray, normal);
        // No back face, parallel rays, or a player standing in the plane.
        if (denominator >= -1e-9 || dot(minus(eye, image.upperMaxX()), normal) <= 1e-9)
            return Optional.empty();
        double distance = dot(minus(image.upperMaxX(), eye), normal) / denominator;
        if (!Double.isFinite(distance) || distance <= 0 || distance > maxDistance) return Optional.empty();
        Point horizontal = minus(image.upperMinX(), image.upperMaxX());
        Point vertical = minus(image.lowerMaxX(), image.upperMaxX());
        double hh = dot(horizontal, horizontal), vv = dot(vertical, vertical), hv = dot(horizontal, vertical);
        double determinant = hh * vv - hv * hv;
        if (!Double.isFinite(determinant) || determinant <= 1e-20) return Optional.empty();
        // Reject malformed/nonplanar/non-parallelogram input instead of mapping a different image.
        Point opposite = minus(minus(image.lowerMinX(), image.upperMaxX()), add(horizontal, vertical));
        if (dot(opposite, opposite) > 1e-16 || Math.abs(dot(horizontal, normal)) > 1e-8
                || Math.abs(dot(vertical, normal)) > 1e-8) return Optional.empty();
        Point delta = minus(add(eye, scale(ray, distance)), image.upperMaxX());
        double dh = dot(delta, horizontal), dv = dot(delta, vertical);
        double u = (dh * vv - dv * hv) / determinant, v = (dv * hh - dh * hv) / determinant;
        // Tiny tolerance only absorbs the arithmetic error at the four exact edges.
        if (!Double.isFinite(u) || !Double.isFinite(v) || u < -1e-10 || u > 1 + 1e-10
                || v < -1e-10 || v > 1 + 1e-10) return Optional.empty();
        u = Math.clamp(u, 0, 1); v = Math.clamp(v, 0, 1);
        return Optional.of(new Pixel(Math.min(width - 1, (int) (u * width)),
                Math.min(height - 1, (int) (v * height)), u, v, distance));
    }

    private static boolean finite(Point p) { return p != null && Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()); }
    private static Point minus(Point a, Point b) { return new Point(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()); }
    private static Point add(Point a, Point b) { return new Point(a.x()+b.x(), a.y()+b.y(), a.z()+b.z()); }
    private static Point scale(Point p, double n) { return new Point(p.x()*n, p.y()*n, p.z()*n); }
    private static double dot(Point a, Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
}
