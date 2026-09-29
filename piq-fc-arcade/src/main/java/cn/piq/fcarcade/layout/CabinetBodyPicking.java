package cn.piq.fcarcade.layout;

import java.util.List;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;

/** Conservative solid silhouettes, independent of the simple decorative selection outline. */
public final class CabinetBodyPicking {
    private CabinetBodyPicking(){}
    public static List<Box> boxes(boolean dual,boolean compact,boolean portrait,int turns){
        return portrait?CabinetOutlineGeometry.boxes(true,true,turns):dual?CabinetOutlineGeometry.boxes(false,compact,turns):RocketArcadeGeometry.collisionBoxes(turns);
    }
    public static List<Box> mountBoxes(boolean dual,boolean compact,boolean portrait,int turns){
        return CabinetModelSolids.boxes(dual,compact,portrait,turns);
    }
    /** First segment intersection, or infinity. Starting inside a body is blocked too. */
    public static double firstHit(List<Box> boxes,Point from,Point to){
        double nearest=Double.POSITIVE_INFINITY;
        for(var box:boxes){
            double lo=0,hi=1;
            double[] a={from.x(),from.y(),from.z()},b={to.x(),to.y(),to.z()};
            double[] min={box.minX(),box.minY(),box.minZ()},max={box.maxX(),box.maxY(),box.maxZ()};
            for(int axis=0;axis<3;axis++){
                double d=b[axis]-a[axis];
                if(!Double.isFinite(a[axis])||!Double.isFinite(b[axis]))return 0;
                if(Math.abs(d)<1e-12){if(a[axis]<min[axis]||a[axis]>max[axis]){hi=-1;break;}}
                else{
                    double t=(min[axis]-a[axis])/d,s=(max[axis]-a[axis])/d;
                    lo=Math.max(lo,Math.min(t,s));hi=Math.min(hi,Math.max(t,s));
                    if(lo>hi)break;
                }
            }
            if(lo<=hi)nearest=Math.min(nearest,lo);
        }
        return nearest;
    }
}
