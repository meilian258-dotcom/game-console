package cn.piq.fcarcade.world;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetFootprintTest {
    @Test void twelveUniqueCellsKeepAnchorFirstAndRotateTwoByThreeByTwo() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            var cells = DualCabinetFootprint.cells(facing);
            assertEquals(12, cells.size());
            assertEquals(12, cells.stream().map(c -> List.of(c.x(), c.y(), c.z())).distinct().count());
            assertEquals(new DualCabinetFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertEquals(2, cells.stream().map(DualCabinetFootprint.Cell::x).distinct().count());
            assertEquals(3, cells.stream().map(DualCabinetFootprint.Cell::y).distinct().count());
            assertEquals(2, cells.stream().map(DualCabinetFootprint.Cell::z).distinct().count());
            for (int part = 0; part < 12; part++) {
                int x=part%2, y=(part/2)%3, z=part/6;
                var expected = switch(facing) {
                    case NORTH -> new DualCabinetFootprint.Cell(part,x,y,z);
                    case EAST -> new DualCabinetFootprint.Cell(part,-z,y,x);
                    case SOUTH -> new DualCabinetFootprint.Cell(part,-x,y,-z);
                    case WEST -> new DualCabinetFootprint.Cell(part,z,y,-x);
                };
                assertEquals(expected, cells.get(part));
            }
        }
    }

    @Test void collisionCellsPartitionFullBodyAndEachSelectionIsOneWholeFrame() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            var whole = DualCabinetFootprint.bounds(facing); double volume=0; int occupied=0;
            for (var cell : DualCabinetFootprint.cells(facing)) {
                var collision=DualCabinetFootprint.clipped(facing,cell.part());
                double cellVolume=(collision.maxX()-collision.minX())*(collision.maxY()-collision.minY())
                        *(collision.maxZ()-collision.minZ());
                if(cell.y()==2) assertEquals(0,cellVolume);
                else { assertTrue(cellVolume>0); occupied++; }
                assertTrue(collision.minX()>=0 && collision.minY()>=0 && collision.minZ()>=0);
                assertTrue(collision.maxX()<=16 && collision.maxY()<=16 && collision.maxZ()<=16);
                volume += cellVolume;
                var selection=DualCabinetFootprint.selection(facing,cell.part());
                assertEquals(whole.minX(),selection.minX()+cell.x()*16,1e-12);
                assertEquals(whole.minY(),selection.minY()+cell.y()*16,1e-12);
                assertEquals(whole.minZ(),selection.minZ()+cell.z()*16,1e-12);
                assertEquals(whole.maxX(),selection.maxX()+cell.x()*16,1e-12);
                assertEquals(whole.maxY(),selection.maxY()+cell.y()*16,1e-12);
                assertEquals(whole.maxZ(),selection.maxZ()+cell.z()*16,1e-12);
            }
            assertEquals(8,occupied);
            assertEquals(24*32*17.6,volume,1e-8);
        }
    }

    @Test void newBoundsMatchUnscaledUserCabinetAndRotateAroundAnchorCentre() {
        double back=32, front=back-17.6;
        var expected=List.of(
                new DualCabinetFootprint.Bounds(4,0,front,28,32,back),
                new DualCabinetFootprint.Bounds(16-back,0,4,16-front,32,28),
                new DualCabinetFootprint.Bounds(-12,0,16-back,12,32,16-front),
                new DualCabinetFootprint.Bounds(front,0,-12,back,32,12));
        for(var facing:DualCabinetFootprint.Facing.values())
            assertEquals(expected.get(facing.ordinal()),DualCabinetFootprint.bounds(facing));
    }

    @Test void anyOccupiedOrProtectedCellRejectsTheWholePlacement() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            var visited=new HashSet<Integer>();
            assertTrue(DualCabinetFootprint.canPlace(facing,c -> visited.add(c.part())));
            assertEquals(12,visited.size());
            for (int denied=0;denied<12;denied++) {
                int no=denied;
                assertFalse(DualCabinetFootprint.canPlace(facing,c -> c.part()!=no));
            }
        }
    }

    @Test void invalidPartsCannotWrapIntoOtherCells() {
        for (int part:new int[]{-1,12,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->DualCabinetFootprint.cell(DualCabinetFootprint.Facing.NORTH,part));
    }

    public static void main(String[] args) throws Exception {
        int count=0;
        for (Object suite:List.of(new DualCabinetFootprintTest(),new DualCabinetAssemblyLedgerTest(),
                new DualCabinetRemovalGateTest(),new DualCabinetWiringTest()))
            for(var method:suite.getClass().getDeclaredMethods())
                if(method.isAnnotationPresent(Test.class)){method.invoke(suite);count++;}
        System.out.println("Passed "+count+" DualCabinet lifecycle checks.");
    }
}
