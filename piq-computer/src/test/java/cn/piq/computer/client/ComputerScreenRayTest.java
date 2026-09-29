package cn.piq.computer.client;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerScreenRayTest {
    record Scene(Map<BlockPos,VoxelShape> shells, ScreenSurfaceGeometry.Surface surface) {}
    static Vec3 v(Point p){return new Vec3(p.x(),p.y(),p.z());}
    static Vec3 at(Scene s,double u,double v){var q=s.surface.image();return v(q.upperMaxX()).lerp(v(q.upperMinX()),u).lerp(v(q.lowerMaxX()).lerp(v(q.lowerMinX()),u),v);}
    static Scene scene(boolean wide,boolean wall,int turn){
        var style=wide?(wall?ArcadeDisplayStyle.HOME_PANEL_3_WALL:ArcadeDisplayStyle.HOME_PANEL_3):(wall?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_2);
        Map<BlockPos,VoxelShape> shells=new HashMap<>();var facing=PanelTvFootprint.Facing.values()[turn];
        for(var cell:PanelTvFootprint.cells(facing,wide)){
            var b=PanelTvFootprint.clipped(facing,cell.part(),wide,wall);
            shells.put(new BlockPos(cell.x(),cell.y(),cell.z()),Shapes.box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16));
        }
        return new Scene(shells,ScreenSurfaceGeometry.frame(style,turn,1,1,false,ScreenAspectFit.Aspect.FOUR_THREE));
    }
    static boolean clear(Scene s,Vec3 eye,Vec3 end){return ComputerScreenRay.clear(eye,end,p->true,s.shells::containsKey,p->s.shells.getOrDefault(p,Shapes.empty()));}

    @Test void reproducesOldAnchorOnlyRestriction(){
        var s=scene(false,false,0);var end=at(s,.1,.1);var eye=end.add(v(s.surface.image().normal()).scale(2));
        assertFalse(ComputerScreenRay.clear(eye,end,p->true,BlockPos.ZERO::equals,p->s.shells.getOrDefault(p,Shapes.empty())));
        assertTrue(clear(s,eye,end));
    }
    @Test void allPanelSizesMountingsFacingsAndImageGridAreReachable(){
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true})for(int t=0;t<4;t++){
            var s=scene(wide,wall,t);var normal=v(s.surface.image().normal());
            for(double u:new double[]{.001,.1,.25,.5,.75,.9,.999})for(double f:new double[]{.001,.1,.25,.5,.75,.9,.999}){
                var end=at(s,u,f);var eye=at(s,.5,.5).add(normal.scale(2));var direction=end.subtract(eye);
                var hit=ScreenRayMapping.hit(s.surface.image(),new Point(eye.x,eye.y,eye.z),new Point(direction.x,direction.y,direction.z),8,640,480).orElseThrow();
                assertEquals(u,hit.u(),1e-9);assertEquals(f,hit.v(),1e-9);
                assertTrue(clear(s,eye,end),"panel "+wide+"/"+wall+"/"+t+" @ "+u+","+f);
            }
        }
    }
    @Test void foreignShellsWallsAndMissingChunksStillBlock(){
        var s=scene(false,false,0);var end=at(s,.1,.1);var eye=end.add(0,0,-2);
        // No valid ownership (foreign TV, stale proxy): its real collision remains an obstacle.
        assertFalse(ComputerScreenRay.clear(eye,end,p->true,p->false,p->s.shells.getOrDefault(p,Shapes.empty())));
        assertFalse(ComputerScreenRay.clear(eye,end,p->p.getZ()!=-1,s.shells::containsKey,p->s.shells.getOrDefault(p,Shapes.empty())));
        assertFalse(ComputerScreenRay.clear(eye,end,p->true,s.shells::containsKey,p->p.getZ()==-1?Shapes.block():s.shells.getOrDefault(p,Shapes.empty())));
        // A verified own shell in front must not hide a foreign obstruction behind it.
        assertFalse(ComputerScreenRay.clear(new Vec3(.5,.5,-2),new Vec3(.5,.5,.5),p->true,p->p.getZ()==-2,p->p.getZ()==-1?Shapes.block():Shapes.empty()));
    }
    @Test void offscreenAndExcessiveRangeRemainRejected(){
        var s=scene(false,false,0);var end=at(s,-.01,.5);var eye=end.add(0,0,-2);var d=end.subtract(eye);
        assertTrue(ScreenRayMapping.hit(s.surface.image(),new Point(eye.x,eye.y,eye.z),new Point(d.x,d.y,d.z),8,640,480).isEmpty());
        assertFalse(clear(s,end.add(0,0,-9),end));assertFalse(clear(s,end,end));
    }
    @Test void worldWiringUsesVerifiedTelevisionOwnership()throws Exception{
        var base=Path.of("src/main/java/cn/piq/computer/client");
        var ray=Files.readString(base.resolve("ComputerScreenRay.java"));
        assertTrue(ray.contains("television.equals(HomeTvStructure.resolveAnchor(level,pos))"));
        assertTrue(Files.readString(base.resolve("ComputerWorldInput.java")).contains("ComputerScreenRay.visible("));
        assertFalse(Files.readString(base.resolve("ComputerWorldInput.java")).contains("ArcadeStructure.resolve(mc.level,hit.getBlockPos())"));
    }
}
