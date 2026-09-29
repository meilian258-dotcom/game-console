package cn.piq.fcarcade.home;

/** Pure bounded ray/box picking; coordinates are already in model units or blocks. */
public final class ApplianceRay {
    private ApplianceRay() {}
    public record Point(double x, double y, double z) {
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Box(HomeApplianceControl control, Point min, Point max) {}
    public static Box units(HomeApplianceControl control, double x0, double y0, double z0,
                            double x1, double y1, double z1) {
        return new Box(control, new Point(x0 / 16, y0 / 16, z0 / 16), new Point(x1 / 16, y1 / 16, z1 / 16));
    }
    public static HomeApplianceControl pick(Point eye, Point end, Box... boxes) {
        if (!eye.finite() || !end.finite()) return HomeApplianceControl.NONE;
        double best = Double.POSITIVE_INFINITY;
        HomeApplianceControl result = HomeApplianceControl.NONE;
        for (Box box : boxes) {
            double t = intersection(eye, end, box);
            if (t < best) { best = t; result = box.control(); }
        }
        return result;
    }
    public static double intersection(Point eye, Point end, Box box) {
        if (!eye.finite() || !end.finite() || !box.min().finite() || !box.max().finite()) return Double.POSITIVE_INFINITY;
        double[] a = {eye.x, eye.y, eye.z}, b = {end.x, end.y, end.z};
        double[] lo = {box.min.x, box.min.y, box.min.z}, hi = {box.max.x, box.max.y, box.max.z};
        double first = 0, last = 1;
        boolean moving = false;
        for (int i = 0; i < 3; i++) {
            if (lo[i] > hi[i]) return Double.POSITIVE_INFINITY;
            double d = b[i] - a[i];
            if (Math.abs(d) < 1e-12) {
                if (a[i] < lo[i] || a[i] > hi[i]) return Double.POSITIVE_INFINITY;
            } else {
                moving = true;
                double near = (lo[i] - a[i]) / d, far = (hi[i] - a[i]) / d;
                first = Math.max(first, Math.min(near, far)); last = Math.min(last, Math.max(near, far));
                if (first > last) return Double.POSITIVE_INFINITY;
            }
        }
        return moving ? first : Double.POSITIVE_INFINITY;
    }
    /** Inverse of north/east/south/west rotation around the anchor center. */
    public static Point unrotate(Point p, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Point(p.z, p.y, 1 - p.x);
            case 2 -> new Point(1 - p.x, p.y, 1 - p.z);
            case 3 -> new Point(1 - p.z, p.y, p.x);
            default -> p;
        };
    }
}
