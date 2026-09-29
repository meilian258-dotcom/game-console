package cn.piq.fcarcade.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class CabinetPowerGeometryTest {
    @Test void frontOnlyAllModelsFacingsAndFootprints(){
        for(boolean compact:new boolean[]{false,true})for(int kind=0;kind<3;kind++)for(int turn=0;turn<4;turn++){
            var boxes=kind==2?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(kind==1,compact);
            assertEquals(1,boxes.size());var b=boxes.getFirst();
            double x=(b.minX()+b.maxX())/2,y=(b.minY()+b.maxY())/2,z=b.minZ();
            var eye=RocketArcadeGeometry.rotate(new Point(x,y,z-2),turn);
            var end=RocketArcadeGeometry.rotate(new Point(x,y,z+.1),turn);
            assertTrue(CabinetPowerGeometry.hits(boxes,turn,eye,end));
            assertFalse(CabinetPowerGeometry.hits(boxes,turn,end,eye));
            assertNull(CabinetPowerGeometry.intersection(boxes,turn,eye,RocketArcadeGeometry.rotate(new Point(x,y,z-.01),turn)));
            for(double side:new double[]{-2,3}){
                assertFalse(CabinetPowerGeometry.hits(boxes,turn,RocketArcadeGeometry.rotate(new Point(side,1.85,.6),turn),RocketArcadeGeometry.rotate(new Point(.5,1.85,.6),turn)));
            }
        }
    }
    @Test void rejectsNonfinite(){assertFalse(CabinetPowerGeometry.hits(false,false,0,new Point(Double.NaN,0,0),new Point(0,0,0)));}
    @Test void expandedTouchTargetDoesNotEnlargeVisibleRocker(){
        var boxes=CabinetPowerGeometry.boxes(true,true);var b=boxes.getFirst();double z=b.minZ();
        assertEquals(.08,b.maxX()-b.minX(),1e-9);assertEquals(.11,b.maxY()-b.minY(),1e-9);
        for(int t=0;t<4;t++)for(double x:new double[]{b.minX()-.099,b.maxX()+.099})for(double y:new double[]{b.minY()-.099,b.maxY()+.099}){
            var hit=CabinetPowerGeometry.intersection(boxes,t,RocketArcadeGeometry.rotate(new Point(x,y,z-2),t),RocketArcadeGeometry.rotate(new Point(x,y,z+.1),t));
            assertNotNull(hit);var local=RocketArcadeGeometry.rotate(hit,-t);assertEquals(z,local.z(),1e-9);
        }
        assertFalse(CabinetPowerGeometry.hits(boxes,0,new Point(b.maxX()+.101,b.maxY(),z-2),new Point(b.maxX()+.101,b.maxY(),z+.1)));
    }
    @Test void realModelBlankPanelAllowsEyeHeightRayInAllFacings(){
        for(boolean compact:new boolean[]{false,true})for(int kind=0;kind<3;kind++)for(int t=0;t<4;t++){
            var b=(kind==2?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(kind==1,compact)).getFirst();
            var hit=CabinetPowerGeometry.meshPoint(b,0,0,0);
            for(double eyeHeight:new double[]{hit.y(),1.62}){
                var eye=RocketArcadeGeometry.rotate(new Point(hit.x(),eyeHeight,hit.z()-2),t);
                var end=RocketArcadeGeometry.rotate(hit,t);
                var solids=CabinetBodyPicking.mountBoxes(kind==1,compact,kind==2,t);
                double first=CabinetBodyPicking.firstHit(solids,eye,end);
                double length=Math.sqrt(Math.pow(eye.x()-end.x(),2)+Math.pow(eye.y()-end.y(),2)+Math.pow(eye.z()-end.z(),2));
                assertTrue(first>=1||length*(1-first)<.001,"Blocked front rocker kind="+kind+" t="+t+" height="+eyeHeight+" gap="+length*(1-first));
                var obstruction=List.of(new Box(Math.min(eye.x(),end.x())-.1,0,Math.min(eye.z(),end.z())-.1,Math.max(eye.x(),end.x())+.1,3,Math.max(eye.z(),end.z())+.1));
                assertEquals(0,CabinetBodyPicking.firstHit(obstruction,eye,end));
            }
        }
    }
}
