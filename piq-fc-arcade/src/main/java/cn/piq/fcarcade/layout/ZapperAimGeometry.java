package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import java.util.ArrayList;
import java.util.List;

/** Bounded grid traversal shared by actual world occlusion and pure regression tests. */
public final class ZapperAimGeometry {
    public static final double MAX_DISTANCE=16;
    public record Cell(int x,int y,int z) {}
    private ZapperAimGeometry() {}
    public static List<Cell> cells(Point from,Point to) {
        if (!finite(from)||!finite(to)) return List.of();
        double dx=to.x()-from.x(),dy=to.y()-from.y(),dz=to.z()-from.z();
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if (!Double.isFinite(length)||length>MAX_DISTANCE+.00001) return List.of();
        int x=(int)Math.floor(from.x()),y=(int)Math.floor(from.y()),z=(int)Math.floor(from.z());
        int endX=(int)Math.floor(to.x()),endY=(int)Math.floor(to.y()),endZ=(int)Math.floor(to.z());
        int sx=Double.compare(dx,0),sy=Double.compare(dy,0),sz=Double.compare(dz,0);
        double tx=first(from.x(),x,dx,sx),ty=first(from.y(),y,dy,sy),tz=first(from.z(),z,dz,sz);
        double ax=dx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx),ay=dy==0?Double.POSITIVE_INFINITY:Math.abs(1/dy),az=dz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        var result=new ArrayList<Cell>(52);
        for(int i=0;i<64;i++) {
            result.add(new Cell(x,y,z));if(x==endX&&y==endY&&z==endZ)return List.copyOf(result);
            // A negative-direction endpoint exactly on an integer face belongs
            // to floor(endpoint), not the cell beyond it. Do not overshoot an
            // already finished axis while the other axes complete their tie.
            if(x==endX)tx=Double.POSITIVE_INFINITY;
            if(y==endY)ty=Double.POSITIVE_INFINITY;
            if(z==endZ)tz=Double.POSITIVE_INFINITY;
            // At a shared face/edge, visit each entered voxel conservatively.
            if(tx<=ty&&tx<=tz){x+=sx;tx+=ax;}else if(ty<=tz){y+=sy;ty+=ay;}else{z+=sz;tz+=az;}
        }
        return List.of(); // Invalid traversal is not an unobstructed path.
    }
    public static boolean beforeScreen(double obstacleDistance,double screenDistance) {
        return !Double.isFinite(obstacleDistance)||!Double.isFinite(screenDistance)
                ||obstacleDistance<screenDistance-.0001;
    }
    private static double first(double origin,int cell,double direction,int step) {
        return direction==0?Double.POSITIVE_INFINITY:((step>0?cell+1:cell)-origin)/direction;
    }
    private static boolean finite(Point p) {return p!=null&&Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z())
            &&Math.abs(p.x())<30_000_001&&Math.abs(p.y())<30_000_001&&Math.abs(p.z())<30_000_001;}
}
