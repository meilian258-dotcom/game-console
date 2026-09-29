package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.List;

/** One-cell knob CRT: geometry units are 1/16 block, screen/socket coordinates are blocks. */
public final class VintageTvLayout {
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private static final List<ScreenQuad> SCREENS=List.of(screenAt(0),screenAt(1),screenAt(2),screenAt(3));
    private VintageTvLayout() {}
    public static Bounds bounds(int turns) {
        Point a=RocketArcadeGeometry.rotate(new Point(.2/16,0,1.8/16),turns);
        Point b=RocketArcadeGeometry.rotate(new Point(15.8/16,14.3/16,14.2/16),turns);
        return new Bounds(Math.min(a.x(),b.x())*16,0,Math.min(a.z(),b.z())*16,
                Math.max(a.x(),b.x())*16,14.3,Math.max(a.z(),b.z())*16);
    }
    public static Point socket(int turns,int channel) {
        if(channel<0||channel>2)throw new IllegalArgumentException("Invalid RCA channel");
        return RocketArcadeGeometry.rotate(new Point((9.5-channel*2)/16,3.1/16,14.04/16),turns);
    }
    public static ScreenQuad screen(int turns){return SCREENS.get(Math.floorMod(turns,4));}
    private static ScreenQuad screenAt(int turns) {
        Point n=RocketArcadeGeometry.rotate(new Point(.5,0,-.5),turns);
        return new ScreenQuad(point(4.35,2.0,turns),point(14.55,2.0,turns),point(14.55,9.65,turns),point(4.35,9.65,turns),
                new Point(n.x()-.5,0,n.z()-.5));
    }
    private static Point point(double x,double y,int turns){return RocketArcadeGeometry.rotate(new Point(x/16,y/16,3.35/16-.0015),turns);}
}
