package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class HomeTvFootprintTest {
    @Test void allFourFacingsHaveEightUniqueCellsAndOneAnchor() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var cells = HomeTvFootprint.cells(facing);
            assertEquals(8, cells.size());
            assertEquals(8, new HashSet<>(cells).size());
            assertEquals(new HomeTvFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::x).distinct().count());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::y).distinct().count());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::z).distinct().count());
        }
    }

    @Test void rotationUsesVanillaAnchorCentreAndIncludesNegativeOffsets() {
        assertEquals(new HomeTvFootprint.Cell(7, 1, 1, 1), HomeTvFootprint.cell(HomeTvFootprint.Facing.NORTH, 7));
        assertEquals(new HomeTvFootprint.Cell(7, -1, 1, 1), HomeTvFootprint.cell(HomeTvFootprint.Facing.EAST, 7));
        assertEquals(new HomeTvFootprint.Cell(7, -1, 1, -1), HomeTvFootprint.cell(HomeTvFootprint.Facing.SOUTH, 7));
        assertEquals(new HomeTvFootprint.Cell(7, 1, 1, -1), HomeTvFootprint.cell(HomeTvFootprint.Facing.WEST, 7));
    }

    @Test void anyBlockedOrUnloadedCellRejectsTheWholeStructure() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            assertTrue(HomeTvFootprint.canPlace(facing, cell -> true));
            for (int blocked = 0; blocked < 8; blocked++) {
                int rejected = blocked;
                assertFalse(HomeTvFootprint.canPlace(facing, cell -> cell.part() != rejected));
            }
        }
    }

    @Test void clippedShapesCoverFullModelWithoutExtendingOutsideOccupiedCells() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var full = HomeTvFootprint.bounds(facing);
            double totalVolume = 0;
            for (var cell : HomeTvFootprint.cells(facing)) {
                var box = HomeTvFootprint.clipped(facing, cell.part());
                assertTrue(box.minX() >= 0 && box.minY() >= 0 && box.minZ() >= 0);
                assertTrue(box.maxX() <= 16 && box.maxY() <= 16 && box.maxZ() <= 16);
                assertTrue(box.maxX() > box.minX() && box.maxY() > box.minY() && box.maxZ() > box.minZ());
                totalVolume += (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ());
            }
            assertEquals((full.maxX() - full.minX()) * (full.maxY() - full.minY())
                    * (full.maxZ() - full.minZ()), totalVolume, 1e-7);
        }
    }

    @Test void everyHitPartHasTheSameWholeTelevisionSelectionOutlineInWorldSpace() {
        for (var facing : HomeTvFootprint.Facing.values()) for (var cell : HomeTvFootprint.cells(facing)) {
            var outline = HomeTvFootprint.selection(facing, cell.part());
            var full = HomeTvFootprint.bounds(facing);
            assertEquals(full.minX(), outline.minX() + cell.x() * 16, 1e-9);
            assertEquals(full.minY(), outline.minY() + cell.y() * 16, 1e-9);
            assertEquals(full.minZ(), outline.minZ() + cell.z() * 16, 1e-9);
            assertEquals(full.maxX(), outline.maxX() + cell.x() * 16, 1e-9);
            assertEquals(full.maxY(), outline.maxY() + cell.y() * 16, 1e-9);
            assertEquals(full.maxZ(), outline.maxZ() + cell.z() * 16, 1e-9);
        }
    }
}
