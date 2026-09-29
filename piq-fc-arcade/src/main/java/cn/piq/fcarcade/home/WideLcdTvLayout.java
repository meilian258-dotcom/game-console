package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.List;

/** New 1.5-block-wide LCD. Old LcdTvLayout and its single-cell save stay unchanged. */
public final class WideLcdTvLayout {
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    public static final double SCREEN_OFFSET = .0015;
    private static final List<ScreenQuad> SCREENS=List.of(screenAt(0),screenAt(1),screenAt(2),screenAt(3));
    private WideLcdTvLayout() {}
    public static Bounds bounds(int turns) {
        Point a=RocketArcadeGeometry.rotate(new Point(0,0,4.8/16),turns);
        Point b=RocketArcadeGeometry.rotate(new Point(24D/16,15D/16,11.2/16),turns);
        return new Bounds(Math.min(a.x(),b.x())*16,0,Math.min(a.z(),b.z())*16,
                Math.max(a.x(),b.x())*16,15,Math.max(a.z(),b.z())*16);
    }
    public static ScreenQuad screen(int turns) { return SCREENS.get(Math.floorMod(turns,4)); }
    public static Point socket(int turns,int channel) {
        if(channel<0||channel>2)throw new IllegalArgumentException("RCA channel outside yellow/white/red");
        return RocketArcadeGeometry.rotate(new Point((9D+channel*3)/16,4D/16,8.23/16),turns);
    }
    private static ScreenQuad screenAt(int turns) {
        Point n=RocketArcadeGeometry.rotate(new Point(.5,0,-.5),turns);
        return new ScreenQuad(point(1,1.5,turns),point(23,1.5,turns),point(23,13.875,turns),point(1,13.875,turns),
                new Point(n.x()-.5,0,n.z()-.5));
    }
    private static Point point(double x,double y,int turns) {
        return RocketArcadeGeometry.rotate(new Point(x/16,y/16,6D/16-SCREEN_OFFSET),turns);
    }
}
