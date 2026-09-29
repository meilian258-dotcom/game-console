package cn.piq.fcarcade.furniture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FurnitureLayoutTest {
    @Test void eachRotationKeepsTwoSeparateSeatsInsideTheirOwnCells() {
        for(int turn=0;turn<4;turn++) {
            var second=FurnitureLayout.second(turn);var first=FurnitureLayout.seat(true,0,turn);var last=FurnitureLayout.seat(true,1,turn);
            assertEquals(.5,first.x());assertEquals(.5,first.z());assertEquals(second.x(),(int)Math.floor(last.x()));assertEquals(second.z(),(int)Math.floor(last.z()));
            assertEquals(1,Math.hypot(first.x()-last.x(),first.z()-last.z()),1e-12);assertEquals(.5,first.y());
        }
    }
    @Test void stoolRemainsCenteredAtItsOriginalSeatHeight() {
        for(int turn=0;turn<4;turn++){var p=FurnitureLayout.seat(false,0,turn);assertEquals(.5,p.x());assertEquals(.5,p.z());assertEquals(6.1513896/16,p.y(),1e-12);}
    }
    @Test void extraSeatsAreNeverWrappedIntoAnOccupiedPosition() {
        assertThrows(IllegalArgumentException.class,()->FurnitureLayout.seat(false,1,0));assertThrows(IllegalArgumentException.class,()->FurnitureLayout.seat(true,2,0));
        assertThrows(IllegalArgumentException.class,()->FurnitureLayout.seat(true,-1,0));
    }
    @Test void fourRotationsPreserveInputAndSpeciesRecipesAreExact() {
        var p=new FurnitureLayout.Point(1.7,.2,.1);var q=p;for(int i=0;i<4;i++)q=FurnitureLayout.rotate(q,1);
        assertEquals(p.x(),q.x(),1e-12);assertEquals(p.z(),q.z(),1e-12);assertEquals(11,WoodSpecies.values().length);
        for(var wood:WoodSpecies.values())assertEquals("minecraft:"+wood.id()+"_planks",wood.planks());
    }
}
