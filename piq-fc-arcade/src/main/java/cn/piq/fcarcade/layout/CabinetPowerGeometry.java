package cn.piq.fcarcade.layout;

import java.util.List;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Box;

/** Front-left rocker, sharing exact visual and server-ray geometry. */
public final class CabinetPowerGeometry {
    private CabinetPowerGeometry(){}
    public static List<Box> boxes(boolean dual,boolean compact){
        // Looking at the screen, left is local +X. Keep clear of the coin door and outer trim.
        return List.of(frontBox(dual?DualCabinetGeometry.MODEL_X_OFFSET+21.85/16:12.6/16,
                (dual?9.75:11.4)/16,(dual?DualCabinetGeometry.modelZOffset(compact):0)+3.02/16));
    }
    public static Box frontBox(double x,double y,double panelZ){
        double z=panelZ+.0005;
        return new Box(x-CabinetPowerMesh.HALF_WIDTH,y-CabinetPowerMesh.HALF_HEIGHT,z,
                x+CabinetPowerMesh.HALF_WIDTH,y+CabinetPowerMesh.HALF_HEIGHT,z+.002);
    }
    public static Point meshPoint(Box b,double u,double v,double w){
        return new Point((b.minX()+b.maxX())/2+u,(b.minY()+b.maxY())/2+v,b.minZ()-w);
    }
    public static boolean hits(boolean dual,boolean compact,int turns,Point eye,Point end){
        return hits(boxes(dual,compact),turns,eye,end);
    }
    public static boolean hits(List<Box> boxes,int turns,Point eye,Point end){
        return intersection(boxes,turns,eye,end)!=null;
    }
    public static Point intersection(List<Box> boxes,int turns,Point eye,Point end){
        if(!finite(eye)||!finite(end))return null;
        var a=RocketArcadeGeometry.rotate(eye,-turns);var b=RocketArcadeGeometry.rotate(end,-turns);
        for(var box:boxes){
            double z=box.minZ(),dz=b.z()-a.z();
            if(a.z()>=z||dz<=0)continue;
            double t=(z-a.z())/dz;if(t<0||t>1)continue;
            double y=a.y()+(b.y()-a.y())*t,x=a.x()+(b.x()-a.x())*t;
            // Touch target is deliberately larger than the visible rocker, on its mounting side only.
            if(y>=box.minY()-.10&&y<=box.maxY()+.10&&x>=box.minX()-.10&&x<=box.maxX()+.10)
                return RocketArcadeGeometry.rotate(new Point(x,y,z),turns);
        }
        return null;
    }
    private static boolean finite(Point p){return p!=null&&Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z());}
}
