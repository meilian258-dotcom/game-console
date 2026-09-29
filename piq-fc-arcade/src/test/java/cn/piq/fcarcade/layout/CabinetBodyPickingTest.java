package cn.piq.fcarcade.layout;

import java.util.List;
import org.junit.jupiter.api.Test;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class CabinetBodyPickingTest {
    @Test void outlineEmptySpaceNoLongerBlocksButRealCabinetAndWallDo(){
        for(int turn=0;turn<4;turn++){
            var from=RocketArcadeGeometry.rotate(new Point(-1,1.55,.1),turn);
            var to=RocketArcadeGeometry.rotate(new Point(3,1.55,.1),turn);
            assertTrue(CabinetBodyPicking.firstHit(List.of(DualCabinetGeometry.bounds(turn,true)),from,to)<1);
            assertEquals(Double.POSITIVE_INFINITY,CabinetBodyPicking.firstHit(CabinetBodyPicking.boxes(true,true,false,turn),from,to));
            from=RocketArcadeGeometry.rotate(new Point(-1,1.55,.8),turn);to=RocketArcadeGeometry.rotate(new Point(3,1.55,.8),turn);
            assertTrue(CabinetBodyPicking.firstHit(CabinetBodyPicking.boxes(true,true,false,turn),from,to)<1);
        }
        var wall=new Box(0,0,0,1,3,1);
        assertEquals(.25,CabinetBodyPicking.firstHit(List.of(wall),new Point(-1,1.85,.1),new Point(3,1.85,.1)),1e-8);
        assertEquals(0,CabinetBodyPicking.firstHit(List.of(wall),new Point(.5,1,.5),new Point(3,1,.5)));
    }
    @Test void ownInsetMountIsWithinToleranceForAllModelsAndFacings(){
        for(int kind=0;kind<4;kind++)for(int turn=0;turn<4;turn++){
            boolean dual=kind==1||kind==2,compact=kind==2,portrait=kind==3;
            var box=(portrait?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(dual,compact)).getFirst();
            double y=(box.minY()+box.maxY())/2,z=box.minZ(),x=(box.minX()+box.maxX())/2;
            var from=RocketArcadeGeometry.rotate(new Point(x,y,z-2),turn);
            var to=RocketArcadeGeometry.rotate(new Point(x,y,z),turn);
            double t=CabinetBodyPicking.firstHit(CabinetBodyPicking.mountBoxes(dual,compact,portrait,turn),from,to);
            assertTrue(2*(1-t)<=.001,"kind="+kind+" turn="+turn);
            assertTrue(box.maxX()>(dual?1:.7));
        }
    }
    @Test void parallelMissShortRayAndNonfiniteAreSafe(){
        var boxes=List.of(new Box(0,0,0,1,2,1));
        assertEquals(Double.POSITIVE_INFINITY,CabinetBodyPicking.firstHit(boxes,new Point(-1,3,.5),new Point(2,3,.5)));
        assertEquals(Double.POSITIVE_INFINITY,CabinetBodyPicking.firstHit(boxes,new Point(-2,1,.5),new Point(-1,1,.5)));
        assertEquals(0,CabinetBodyPicking.firstHit(boxes,new Point(Double.NaN,1,.5),new Point(2,1,.5)));
    }
}
