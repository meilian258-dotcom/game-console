import cn.piq.fcarcade.layout.DualCabinetGeometry;
import cn.piq.fcarcade.layout.DualCabinetControls;
import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.world.DualCabinetFootprint;

/** Read-only real production geometry export; never loads Minecraft. */
public final class UserDualGeometryProbe {
    private static String point(Point p){return "["+p.x()+","+p.y()+","+p.z()+"]";}
    public static void main(String[] args){
        System.out.print("{\"xOffset\":"+DualCabinetGeometry.MODEL_X_OFFSET+",\"yOffset\":"+DualCabinetGeometry.MODEL_Y_OFFSET
                +",\"screenOffset\":"+DualCabinetGeometry.SCREEN_OFFSET+",\"facings\":[");
        for(int t=0;t<4;t++){
            if(t>0)System.out.print(",");var q=DualCabinetGeometry.screen(t);var b=DualCabinetGeometry.bounds(t);
            var shape=DualCabinetFootprint.bounds(DualCabinetFootprint.Facing.values()[t]);
            double[] visual={b.minX()*16,b.minY()*16,b.minZ()*16,b.maxX()*16,b.maxY()*16,b.maxZ()*16};
            double[] collision={shape.minX(),shape.minY(),shape.minZ(),shape.maxX(),shape.maxY(),shape.maxZ()};
            for(int i=0;i<6;i++)if(Math.abs(visual[i]-collision[i])>1e-8)throw new AssertionError("Collision/render mismatch "+t+"/"+i);
            System.out.print("{\"screen\":["+point(q.lowerMinX())+","+point(q.lowerMaxX())+","+point(q.upperMaxX())+","+point(q.upperMinX())
                    +"],\"normal\":"+point(q.normal())+",\"bounds\":[["+b.minX()+","+b.minY()+","+b.minZ()+"],["+b.maxX()+","+b.maxY()+","+b.maxZ()+"]]}");
        }
        System.out.print("],\"parts\":[");
        for(int i=0;i<DualCabinetControls.PARTS.size();i++){
            if(i>0)System.out.print(",");var p=DualCabinetControls.PARTS.get(i);var m=DualCabinetControls.motion(p,0xF9B,false);
            System.out.print("{\"name\":\""+p.name()+"\",\"player\":"+p.player()+",\"pivot\":["+p.x()+","+p.y()+","+p.z()
                    +"],\"pressY\":"+m.pressY()+",\"tiltX\":"+m.tiltX()+",\"tiltZ\":"+m.tiltZ()+"}");
        }
        var f=CabinetVideoGeometry.frame(true,0,4.0/3,0);
        System.out.print("],\"video\":[");for(int i=0;i<4;i++){
            if(i>0)System.out.print(",");var v=f.vertices().get(i);
            System.out.print("{\"point\":"+point(v.point())+",\"uv\":["+v.u()+","+v.v()+"]}");
        }
        System.out.println("]}");
    }
}
