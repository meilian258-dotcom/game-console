package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HomeTvCenteredFootprintTest {
    @Test void oldSignaturesRetainAllLegacyCoordinatesAndShapes() {
        assertEquals(8, HomeTvFootprint.cellCount(false));
        for (var facing : HomeTvFootprint.Facing.values()) {
            assertEquals(HomeTvFootprint.cells(facing), HomeTvFootprint.cells(facing, false));
            assertEquals(HomeTvFootprint.bounds(facing), HomeTvFootprint.bounds(facing, false));
            assertTrue(HomeTvFootprint.canPlace(facing, false, cell -> true));
            for (int part = 0; part < 8; part++) {
                assertEquals(HomeTvFootprint.cell(facing, part), HomeTvFootprint.cell(facing, part, false));
                assertEquals(HomeTvFootprint.clipped(facing, part), HomeTvFootprint.clipped(facing, part, false));
                assertEquals(HomeTvFootprint.selection(facing, part), HomeTvFootprint.selection(facing, part, false));
            }
        }
        assertEquals(new HomeTvFootprint.Bounds(0, 0, .798, 32, 25.4, 28.91),
                HomeTvFootprint.bounds(HomeTvFootprint.Facing.NORTH));
    }

    @Test void centeredFacingsReserveTwelveUniqueCellsWithAnchorFirst() {
        assertEquals(12, HomeTvFootprint.cellCount(true));
        for (var facing : HomeTvFootprint.Facing.values()) {
            var cells = HomeTvFootprint.cells(facing, true);
            assertEquals(12, cells.size());
            assertEquals(12, cells.stream().map(cell -> List.of(cell.x(), cell.y(), cell.z())).distinct().count());
            assertEquals(new HomeTvFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertEquals(1, cells.stream().filter(cell -> cell.x() == 0 && cell.y() == 0 && cell.z() == 0).count());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::y).distinct().count());
            boolean northSouth = facing == HomeTvFootprint.Facing.NORTH || facing == HomeTvFootprint.Facing.SOUTH;
            assertEquals(northSouth ? 3 : 2, cells.stream().map(HomeTvFootprint.Cell::x).distinct().count());
            assertEquals(northSouth ? 2 : 3, cells.stream().map(HomeTvFootprint.Cell::z).distinct().count());
        }
    }

    @Test void centeredPartNumbersAndFourFacingOffsetsAreStable() {
        int[] columns = {0, -1, 1};
        for (int part = 0; part < 12; part++) {
            int x = columns[part % 3], y = (part / 3) % 2, z = part / 6;
            assertEquals(new HomeTvFootprint.Cell(part, x, y, z), HomeTvFootprint.cell(HomeTvFootprint.Facing.NORTH, part, true));
            assertEquals(new HomeTvFootprint.Cell(part, -z, y, x), HomeTvFootprint.cell(HomeTvFootprint.Facing.EAST, part, true));
            assertEquals(new HomeTvFootprint.Cell(part, -x, y, -z), HomeTvFootprint.cell(HomeTvFootprint.Facing.SOUTH, part, true));
            assertEquals(new HomeTvFootprint.Cell(part, z, y, -x), HomeTvFootprint.cell(HomeTvFootprint.Facing.WEST, part, true));
        }
    }

    @Test void centeredBodyOnlyMovesHalfABlockAcrossItsWidthBeforeRotation() {
        double[][] offsets = {{-8, 0}, {0, -8}, {8, 0}, {0, 8}};
        for (var facing : HomeTvFootprint.Facing.values()) {
            var old = HomeTvFootprint.bounds(facing);
            var centered = HomeTvFootprint.bounds(facing, true);
            double dx = offsets[facing.ordinal()][0], dz = offsets[facing.ordinal()][1];
            assertEquals(old.minX() + dx, centered.minX(), 1e-9);
            assertEquals(old.maxX() + dx, centered.maxX(), 1e-9);
            assertEquals(old.minZ() + dz, centered.minZ(), 1e-9);
            assertEquals(old.maxZ() + dz, centered.maxZ(), 1e-9);
            assertEquals(old.minY(), centered.minY());
            assertEquals(old.maxY(), centered.maxY());
        }
        var north = HomeTvFootprint.bounds(HomeTvFootprint.Facing.NORTH, true);
        assertEquals(-8, north.minX());
        assertEquals(24, north.maxX());
        assertEquals(8, (north.minX() + north.maxX()) / 2);
        assertEquals(32, north.maxX() - north.minX());
    }

    @Test void anyOfTwelveUnavailableCellsRejectsPlacementInEveryFacing() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            assertTrue(HomeTvFootprint.canPlace(facing, true, cell -> true));
            for (int blocked = 0; blocked < 12; blocked++) {
                int unavailable = blocked;
                assertFalse(HomeTvFootprint.canPlace(facing, true, cell -> cell.part() != unavailable));
            }
        }
    }

    @Test void twelveClippedCollisionsPartitionTheSameBodyWithoutOverhangOrGaps() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var full = HomeTvFootprint.bounds(facing, true);
            double volume = 0;
            for (var cell : HomeTvFootprint.cells(facing, true)) {
                var box = HomeTvFootprint.clipped(facing, cell.part(), true);
                assertTrue(box.minX() >= 0 && box.minY() >= 0 && box.minZ() >= 0);
                assertTrue(box.maxX() <= 16 && box.maxY() <= 16 && box.maxZ() <= 16);
                assertTrue(box.maxX() > box.minX() && box.maxY() > box.minY() && box.maxZ() > box.minZ());
                volume += (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ());
            }
            assertEquals((full.maxX() - full.minX()) * (full.maxY() - full.minY())
                    * (full.maxZ() - full.minZ()), volume, 1e-7);
        }
    }

    @Test void allTwelveHitPartsReturnOneIdenticalWorldSpaceSelectionBox() {
        for (var facing : HomeTvFootprint.Facing.values()) for (var cell : HomeTvFootprint.cells(facing, true)) {
            var outline = HomeTvFootprint.selection(facing, cell.part(), true);
            var full = HomeTvFootprint.bounds(facing, true);
            assertEquals(full.minX(), outline.minX() + cell.x() * 16, 1e-9);
            assertEquals(full.minY(), outline.minY() + cell.y() * 16, 1e-9);
            assertEquals(full.minZ(), outline.minZ() + cell.z() * 16, 1e-9);
            assertEquals(full.maxX(), outline.maxX() + cell.x() * 16, 1e-9);
            assertEquals(full.maxY(), outline.maxY() + cell.y() * 16, 1e-9);
            assertEquals(full.maxZ(), outline.maxZ() + cell.z() * 16, 1e-9);
        }
    }

    @Test void partRangeIsLayoutSpecificAndDoesNotWrapMalformedValues() {
        for (var facing : HomeTvFootprint.Facing.values()) for (boolean centered : new boolean[]{false, true}) {
            assertThrows(IllegalArgumentException.class, () -> HomeTvFootprint.cell(facing, -1, centered));
            assertThrows(IllegalArgumentException.class, () -> HomeTvFootprint.cell(facing, HomeTvFootprint.cellCount(centered), centered));
            assertThrows(IllegalArgumentException.class, () -> HomeTvFootprint.cell(facing, Integer.MAX_VALUE, centered));
        }
    }
}
