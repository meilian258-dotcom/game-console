package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;

/** Front-facing ray against the existing coin mechanism, not the whole cabinet door.
 * Coordinates follow the same world translation as the dual renderer, including its rear alignment.
 * Both physical coin mechanisms pay into the caller's own authorized seat. */
public final class CabinetCoinGeometry {
    private CabinetCoinGeometry(){}
    public static boolean hits(boolean dual,int turns,Point eye,Point end){
        return hits(dual,turns,eye,end,false);
    }
    public static boolean hits(boolean dual,int turns,Point eye,Point end,boolean compact){
        if(!finite(eye)||!finite(end))return false;
        var a=RocketArcadeGeometry.rotate(eye,-turns);var b=RocketArcadeGeometry.rotate(end,-turns);
        double z=2.53/16D+(dual?cn.piq.fcarcade.layout.DualCabinetGeometry.modelZOffset(compact):0),dz=b.z()-a.z();
        if(a.z()>=z||dz<=0)return false;
        double t=(z-a.z())/dz;if(t<0||t>1)return false;
        double x=(a.x()+(b.x()-a.x())*t-(dual?.25:0))*16,y=(a.y()+(b.y()-a.y())*t)*16;
        // Full mechanism face is a practical click target; cabinet sides/return tray are not.
        return dual?y>=8.25&&y<=10.27&&(x>=5.44&&x<=6.56||x>=17.44&&x<=18.56)
                :y>=9.05&&y<=11.07&&x>=7.44&&x<=8.56;
    }
    private static boolean finite(Point p){return p!=null&&Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z());}
}
