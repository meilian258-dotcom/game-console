package cn.piq.nativearcade.world;

import cn.piq.fcarcade.world.DualCabinetFootprint;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCabinetFootprintTest {
    @Test void sixUniqueCellsMatchOnlyActiveFrozenFcCells() {
        for(var f:NativeCabinetFootprint.Facing.values()) {
            var cells=NativeCabinetFootprint.cells(f);assertEquals(6,cells.size());
            assertEquals(6,cells.stream().map(c->List.of(c.x(),c.y(),c.z())).distinct().count());
            assertEquals(new NativeCabinetFootprint.Cell(0,0,0,0),cells.getFirst());
            for(var c:cells){var old=DualCabinetFootprint.cell(DualCabinetFootprint.Facing.valueOf(f.name()),c.part());
                assertEquals(List.of(old.x(),old.y(),old.z()),List.of(c.x(),c.y(),c.z()));}
            assertEquals(3,cells.stream().map(NativeCabinetFootprint.Cell::y).distinct().count());
            assertEquals(1,cells.stream().map(c->f==NativeCabinetFootprint.Facing.NORTH||f==NativeCabinetFootprint.Facing.SOUTH?c.z():c.x()).distinct().count());
        }
    }
    @Test void clippedCollisionTilesExactlyTwoBy235ByOne() {
        for(var f:NativeCabinetFootprint.Facing.values()) {
            double volume=0;var all=NativeCabinetFootprint.bounds(f);
            for(var c:NativeCabinetFootprint.cells(f)) {
                var b=NativeCabinetFootprint.clipped(f,c.part());double h=c.part()<4?16:5.6;
                assertEquals(0,b.minX());assertEquals(0,b.minY());assertEquals(0,b.minZ());
                assertEquals(16,b.maxX());assertEquals(h,b.maxY(),1e-9);assertEquals(16,b.maxZ());volume+=16*h*16;
                var s=NativeCabinetFootprint.selection(f,c.part());
                assertEquals(all.minX(),s.minX()+16*c.x());assertEquals(all.minY(),s.minY()+16*c.y());assertEquals(all.minZ(),s.minZ()+16*c.z());
                assertEquals(all.maxX(),s.maxX()+16*c.x());assertEquals(all.maxY(),s.maxY()+16*c.y());assertEquals(all.maxZ(),s.maxZ()+16*c.z());
            }
            assertEquals(32*37.6*16,volume,1e-8);
        }
    }
    @Test void everyUnavailableCellRejectsPlacement() {
        for(var f:NativeCabinetFootprint.Facing.values())for(int denied=0;denied<6;denied++) {
            int d=denied;assertFalse(NativeCabinetFootprint.canPlace(f,c->c.part()!=d));
        }
    }
    @Test void rearRowOrInvalidIdsCannotBeClaimed() {
        for(int p:new int[]{-1,6,11,12,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->NativeCabinetFootprint.cell(NativeCabinetFootprint.Facing.NORTH,p));
    }
    public static void main(String[] args)throws Exception {
        int n=0;
        for(Object suite:List.of(new NativeCabinetFootprintTest(),new NativeCabinetAssemblyLedgerTest(),new NativeCabinetRemovalGateTest(),new cn.piq.nativearcade.layout.NativeCabinetLayoutTest()))
            for(var method:suite.getClass().getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.setAccessible(true);method.invoke(suite);n++;}
        System.out.println("NATIVE_CABINET_PURE_TESTS="+n+" PASS");
    }
}
