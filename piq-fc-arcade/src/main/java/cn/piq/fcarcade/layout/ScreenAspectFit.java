package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;

/** Contain a logical display ratio in the real tilted screen; never crop the source UV. */
public final class ScreenAspectFit {
    public enum Aspect {
        FOUR_THREE("4:3", 4.0 / 3), SQUARE("1:1", 1), WIDE("16:9", 16.0 / 9);
        private final String label;
        private final double ratio;
        Aspect(String label, double ratio) { this.label=label; this.ratio=ratio; }
        public String label() { return label; }
        public double ratio() { return ratio; }
        public static Aspect parse(String value) {
            for (Aspect aspect : values()) if (aspect.label.equals(value)) return aspect;
            throw new IllegalArgumentException("Screen aspect must be 4:3, 1:1 or 16:9");
        }
    }
    private ScreenAspectFit() {}

    public static ScreenQuad fit(ScreenQuad screen, double contentRatio) {
        if (!Double.isFinite(contentRatio) || contentRatio <= 0) throw new IllegalArgumentException("Invalid aspect");
        double width=screen.lowerMinX().distanceTo(screen.lowerMaxX());
        double height=screen.lowerMinX().distanceTo(screen.upperMinX());
        if (!Double.isFinite(width+height) || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid screen");
        double screenRatio=width/height;
        double horizontal=Math.min(1,contentRatio/screenRatio);
        double vertical=Math.min(1,screenRatio/contentRatio);
        if (Math.abs(horizontal-1)<1e-10 && Math.abs(vertical-1)<1e-10) return screen;
        Point center=screen.center();
        return new ScreenQuad(inset(screen.lowerMinX(),screen,center,horizontal,vertical),
                inset(screen.lowerMaxX(),screen,center,horizontal,vertical),
                inset(screen.upperMaxX(),screen,center,horizontal,vertical),
                inset(screen.upperMinX(),screen,center,horizontal,vertical),screen.normal());
    }
    private static Point inset(Point point, ScreenQuad screen, Point center, double horizontal, double vertical) {
        // Resolve the corner against the in-plane horizontal/vertical basis. Using
        // world Y alone would squash a tilted screen and fail when the cabinet turns.
        double sx=(point==screen.lowerMinX() || point==screen.upperMinX()) ? -.5 : .5;
        double sy=(point==screen.lowerMinX() || point==screen.lowerMaxX()) ? -.5 : .5;
        Point left=screen.lowerMinX(),right=screen.lowerMaxX(),top=screen.upperMinX();
        return new Point(center.x()+sx*horizontal*(right.x()-left.x())+sy*vertical*(top.x()-left.x()),
                center.y()+sx*horizontal*(right.y()-left.y())+sy*vertical*(top.y()-left.y()),
                center.z()+sx*horizontal*(right.z()-left.z())+sy*vertical*(top.z()-left.z()));
    }
}
