package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SuborFootprintTest {
    @Test void exactlyFourHorizontalCellsRotateAroundTheSameAuthoritativeBlock() {
        for (var facing : SuborFootprint.Facing.values()) {
            var cells = SuborFootprint.cells(facing);
            assertEquals(4, cells.size()); assertEquals(4, new HashSet<>(cells).size());
            assertEquals(new SuborFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertTrue(cells.stream().allMatch(cell -> cell.y() == 0));
            assertEquals(2, cells.stream().map(SuborFootprint.Cell::x).distinct().count());
            assertEquals(2, cells.stream().map(SuborFootprint.Cell::z).distinct().count());
        }
        assertEquals(new SuborFootprint.Cell(3, -1, 0, 1), SuborFootprint.cell(SuborFootprint.Facing.EAST, 3));
        assertEquals(new SuborFootprint.Cell(3, 1, 0, -1), SuborFootprint.cell(SuborFootprint.Facing.WEST, 3));
        assertThrows(IllegalArgumentException.class, () -> SuborFootprint.cell(SuborFootprint.Facing.NORTH, 4));
    }

    @Test void clippedPartsExactlyCoverTheReviewedWideBoundsWithoutExceedingTheirCell() {
        for (var facing : SuborFootprint.Facing.values()) {
            double volume = 0; var whole = HomeConsoleLayout.suborBounds(facing.ordinal(), true);
            for (var cell : SuborFootprint.cells(facing)) {
                var local = SuborFootprint.clipped(facing, cell.part());
                assertTrue(local.minX() >= 0 && local.minY() >= 0 && local.minZ() >= 0);
                assertTrue(local.maxX() <= 16 && local.maxY() <= 16 && local.maxZ() <= 16);
                assertTrue(local.maxX() > local.minX() && local.maxY() > local.minY() && local.maxZ() > local.minZ());
                volume += (local.maxX()-local.minX()) * (local.maxY()-local.minY()) * (local.maxZ()-local.minZ());
                var fullLocal = SuborFootprint.selection(facing, cell.part());
                assertEquals(whole.minX(), fullLocal.minX() + cell.x()*16, 1e-9);
                assertEquals(whole.maxZ(), fullLocal.maxZ() + cell.z()*16, 1e-9);
            }
            assertEquals((whole.maxX()-whole.minX())*(whole.maxY()-whole.minY())*(whole.maxZ()-whole.minZ()), volume, 1e-7);
        }
    }

    @Test void everyCellParticipatesInTheRealPlacementGate() {
        for (var facing : SuborFootprint.Facing.values()) for (int denied = 0; denied < 4; denied++) {
            final int blocked = denied;
            assertFalse(SuborFootprint.canPlace(facing, cell -> cell.part() != blocked));
        }
        var checked = new HashSet<Integer>();
        assertTrue(SuborFootprint.canPlace(SuborFootprint.Facing.NORTH, cell -> checked.add(cell.part())));
        assertEquals(Set.of(0, 1, 2, 3), checked);
    }

    @Test void compactUsesOnlyTwoWidthCellsAndNeverAsksForTheOldRearRow() {
        for (var facing : SuborFootprint.Facing.values()) {
            var cells = SuborFootprint.cells(facing, true);
            assertEquals(2, cells.size());
            assertEquals(SuborFootprint.cells(facing).subList(0, 2), cells);
            assertThrows(IllegalArgumentException.class, () -> SuborFootprint.cell(facing, 2, true));
            var checked = new HashSet<Integer>();
            assertTrue(SuborFootprint.canPlace(facing, true, cell -> checked.add(cell.part())));
            assertEquals(Set.of(0, 1), checked);
            assertFalse(SuborFootprint.canPlace(facing, true, cell -> cell.part() != 0));
            assertFalse(SuborFootprint.canPlace(facing, true, cell -> cell.part() != 1));
        }
    }

    @Test void compactClippedShapesCoverTheirOwnBoundsInAllFourDirections() {
        for (var facing : SuborFootprint.Facing.values()) {
            var whole = HomeConsoleLayout.suborBounds(facing.ordinal(), true, true);
            double volume = 0;
            for (var cell : SuborFootprint.cells(facing, true)) {
                var box = SuborFootprint.clipped(facing, cell.part(), true);
                assertTrue(box.minX() >= 0 && box.minY() >= 0 && box.minZ() >= 0);
                assertTrue(box.maxX() <= 16 && box.maxY() <= 16 && box.maxZ() <= 16);
                assertTrue(box.maxX() > box.minX() && box.maxY() > box.minY() && box.maxZ() > box.minZ());
                volume += (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ());
                var full = SuborFootprint.selection(facing, cell.part(), true);
                assertEquals(whole.minX(), full.minX() + cell.x() * 16, 1e-9);
                assertEquals(whole.maxZ(), full.maxZ() + cell.z() * 16, 1e-9);
            }
            assertEquals((whole.maxX() - whole.minX()) * (whole.maxY() - whole.minY())
                    * (whole.maxZ() - whole.minZ()), volume, 1e-7);
        }
    }

    public static void main(String[] args) throws Exception {
        int count = 0;
        for (Object suite : java.util.List.of(new SuborFootprintTest(), new SuborAssemblyLedgerTest(),
                new SuborRemovalGateTest(), new SuborConsoleWiringTest()))
            for (var method : suite.getClass().getDeclaredMethods())
                if (method.isAnnotationPresent(Test.class)) { method.invoke(suite); count++; }
        System.out.println("Passed " + count + " Subor structure checks.");
    }
}
