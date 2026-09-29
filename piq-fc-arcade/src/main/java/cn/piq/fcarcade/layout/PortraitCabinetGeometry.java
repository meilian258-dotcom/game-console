package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;
import java.util.List;

/** Uniformly fit the supplied 16x35 model into two blocks; never squeeze its 3:4 glass. */
public final class PortraitCabinetGeometry {
    public static final double SCALE=32D/35, X=(1-SCALE)/2, Z=1-17.98201*SCALE/16;
    private PortraitCabinetGeometry(){}
    public static Point point(double x,double y,double z){return new Point(X+x*SCALE/16,y*SCALE/16,Z+z*SCALE/16);}
    public static Point source(Point p){return new Point((p.x()-X)/SCALE,p.y()/SCALE,(p.z()-Z)/SCALE);}
    public static Box bounds(int turns){
        var a=point(0,0,.3820101);var b=point(16,35,17.98201);
        var corners=List.of(new Point(a.x(),0,a.z()),new Point(a.x(),0,b.z()),new Point(b.x(),0,a.z()),new Point(b.x(),0,b.z())).stream().map(p->RocketArcadeGeometry.rotate(p,turns)).toList();
        return new Box(corners.stream().mapToDouble(Point::x).min().orElseThrow(),0,corners.stream().mapToDouble(Point::z).min().orElseThrow(),corners.stream().mapToDouble(Point::x).max().orElseThrow(),b.y(),corners.stream().mapToDouble(Point::z).max().orElseThrow());
    }
    public static ScreenQuad screen(int turns){
        var n=RocketArcadeGeometry.rotate(new Point(.5,Math.sin(Math.PI/8),.5-Math.cos(Math.PI/8)),turns);
        return new ScreenQuad(screenPoint(2.9375,17.55,turns),screenPoint(13.0625,17.55,turns),
                screenPoint(13.0625,31.05,turns),screenPoint(2.9375,31.05,turns),new Point(n.x()-.5,n.y(),n.z()-.5));
    }
    private static Point screenPoint(double x,double y,int turns){
        double c=Math.cos(Math.PI/8),s=Math.sin(Math.PI/8),dy=y-17,dz=-.2;
        var p=point(x,17+dy*c-dz*s,4.8+dy*s+dz*c);
        return RocketArcadeGeometry.rotate(new Point(p.x(),p.y()+s*.0015,p.z()-c*.0015),turns);
    }
    public static List<Box> powerBoxes(){
        var p=point(13,12.4,3.02);
        return List.of(CabinetPowerGeometry.frontBox(p.x(),p.y(),p.z()));
    }
}
