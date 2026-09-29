package cn.piq.fcarcade.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetCompactTest {
    @Test void compactOccupiesOnlyTwoWideOneDeepAndTwoHighInEveryDirection() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            var cells = DualCabinetFootprint.cells(facing, true);
            assertEquals(4, cells.size());
            assertEquals(new DualCabinetFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertEquals(4, cells.stream().map(c -> List.of(c.x(), c.y(), c.z())).distinct().count());
            assertEquals(2, cells.stream().map(DualCabinetFootprint.Cell::y).distinct().count());
            boolean northSouth = facing == DualCabinetFootprint.Facing.NORTH || facing == DualCabinetFootprint.Facing.SOUTH;
            assertEquals(northSouth ? 2 : 1, cells.stream().map(DualCabinetFootprint.Cell::x).distinct().count());
            assertEquals(northSouth ? 1 : 2, cells.stream().map(DualCabinetFootprint.Cell::z).distinct().count());
            var checked = new HashSet<Integer>();
            assertTrue(DualCabinetFootprint.canPlace(facing, true, c -> checked.add(c.part())));
            assertEquals(java.util.Set.of(0, 1, 2, 3), checked);
            for (int denied = 0; denied < 4; denied++) {
                final int part = denied;
                assertFalse(DualCabinetFootprint.canPlace(facing, true, c -> c.part() != part));
            }
        }
    }

    @Test void controlOverhangIsVisibleButCollisionDoesNotEnterFrontCell() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            double volume = 0;
            var visual = DualCabinetFootprint.bounds(facing, true);
            for (var cell : DualCabinetFootprint.cells(facing, true)) {
                var collision = DualCabinetFootprint.clipped(facing, cell.part(), true);
                assertTrue(collision.minX() >= 0 && collision.minY() >= 0 && collision.minZ() >= 0);
                assertTrue(collision.maxX() <= 16 && collision.maxY() <= 16 && collision.maxZ() <= 16);
                volume += (collision.maxX()-collision.minX()) * (collision.maxY()-collision.minY())
                        * (collision.maxZ()-collision.minZ());
                var selection = DualCabinetFootprint.selection(facing, cell.part(), true);
                assertEquals(visual.minZ(), selection.minZ() + cell.z()*16, 1e-10);
                assertEquals(visual.maxZ(), selection.maxZ() + cell.z()*16, 1e-10);
            }
            assertEquals(24 * 32 * 16, volume, 1e-8);
            if (facing == DualCabinetFootprint.Facing.NORTH) {
                assertEquals(-1.6, visual.minZ(), 1e-10);
                assertEquals(16, visual.maxZ(), 1e-10);
            }
            assertThrows(IllegalArgumentException.class, () -> DualCabinetFootprint.cell(facing, 4, true));
            assertEquals(12, DualCabinetFootprint.cells(facing, false).size());
        }
    }

    @Test void fourCellLedgerSurvivesReloadAndRefundsExactlyOnce() {
        for (var facing : DualCabinetFootprint.Facing.values()) for (int first = 0; first < 4; first++) {
            var ledger = new DualCabinetAssemblyLedger(); var id = UUID.randomUUID();
            var entry = new DualCabinetAssemblyLedger.Assembly(id, 15, 63, 15, facing, false, 0, true);
            assertTrue(ledger.restore(entry));
            var clicked = DualCabinetFootprint.cell(facing, first, true);
            assertTrue(ledger.close(id, first, 15+clicked.x(), 63+clicked.y(), 15+clicked.z()));
            for (var cell : entry.cells()) {
                var reload = new DualCabinetAssemblyLedger();
                for (var saved : ledger.snapshots()) assertTrue(reload.restore(saved));
                ledger = reload;
                assertTrue(ledger.get(id).compact());
                assertFalse(ledger.close(id, cell.part(), 15+cell.x(), 63+cell.y(), 15+cell.z()));
                ledger.acknowledge(id, cell.part());
            }
            assertNull(ledger.get(id));
        }
    }

    @Test void freedCellsNeverAuthorizeDropsOrExtendCompactCleanup() {
        var facing = DualCabinetFootprint.Facing.NORTH; var id = UUID.randomUUID();
        var entry = new DualCabinetAssemblyLedger.Assembly(id, 0, 0, 0, facing, false, 0, true);
        var ledger = new DualCabinetAssemblyLedger(); assertTrue(ledger.restore(entry));
        for (int part = 4; part < 12; part++) {
            var oldCell = DualCabinetFootprint.cell(facing, part);
            assertFalse(entry.owns(part, oldCell.x(), oldCell.y(), oldCell.z()));
            assertFalse(ledger.close(id, part, oldCell.x(), oldCell.y(), oldCell.z()));
        }
        assertFalse(ledger.restore(new DualCabinetAssemblyLedger.Assembly(UUID.randomUUID(), 0, 0, 0, facing, true, 16, true)));
        assertTrue(ledger.close(id, 0, 0, 0, 0));
        ledger.acknowledge(id, 11); assertEquals(0, ledger.get(id).cleared());
        for (int part = 0; part < 4; part++) ledger.acknowledge(id, part);
        assertNull(ledger.get(id));
    }

    @Test void compactRemovalChecksOnlyItsOwnCellsAndRepeatsProtectionAfterCallbacks() {
        for (var facing : DualCabinetFootprint.Facing.values()) {
            var visited = new HashSet<Integer>(); var committed = new AtomicBoolean();
            var result = DualCabinetRemovalGate.attempt(facing, true, 0,
                    c -> c.part() < 4, c -> { visited.add(c.part()); return c.part() < 4; },
                    c -> true, c -> true, () -> true, () -> committed.set(true));
            assertEquals(DualCabinetRemovalGate.Result.REMOVED, result);
            assertTrue(committed.get()); assertEquals(java.util.Set.of(0,1,2,3), visited);
            var allowed = new AtomicBoolean(true); committed.set(false);
            result = DualCabinetRemovalGate.attempt(facing, true, 0, c -> true,
                    c -> allowed.get(), c -> true, c -> { allowed.set(false); return true; },
                    () -> true, () -> committed.set(true));
            assertEquals(DualCabinetRemovalGate.Result.DENIED, result); assertFalse(committed.get());
        }
    }

    @Test void placementAndPersistenceSelectExplicitLayoutWithoutMigratingOldWorlds() throws Exception {
        var dir = Path.of("src/main/java/cn/piq/fcarcade/world");
        var block = Files.readString(dir.resolve("DualCabinetBlock.java"));
        assertTrue(block.contains("defaultBlockState().setValue(COMPACT, false)"));
        assertTrue(block.contains("state = state.setValue(COMPACT, true)"));
        var data = Files.readString(dir.resolve("DualCabinetAssemblyData.java"));
        assertTrue(data.contains("entry.getBoolean(\"Compact\")"));
        assertTrue(data.contains("entry.putBoolean(\"Compact\", assembly.compact())"));
        var structure = Files.readString(dir.resolve("DualCabinetStructure.java"));
        for (String wiring : new String[]{"canPlace(facing(state), compact(state),", "cells(facing(state), compact(state))",
                "cells(facing, tv.compactFootprint())", "entry.cells()", "entry.compact() != tv.compactFootprint()",
                "compact(state) != tv.compactFootprint()", "compact(state) != assembly.compact()"})
            assertTrue(structure.contains(wiring), wiring);
    }

    @Test void removalAdmitsOnlyMatchingLayoutButUsesOldStateDuringOnRemove() throws Exception {
        var dir=Path.of("src/main/java/cn/piq/fcarcade/world");
        var source=Files.readString(dir.resolve("DualCabinetStructure.java"));
        assertTrue(source.contains("facing(state) != assembly.facing() || compact(state) != assembly.compact()"));
        assertTrue(source.contains("ownsState(actual, originalState, pos, entry, actual.part())"));
        assertTrue(source.contains("assembly != null && owns(level, pos, assembly, actual.part())"));
        assertTrue(source.contains("entry == null || !owns(level, pos, entry, actual.part())"));
        assertTrue(source.contains("destroy(server, pos, pos, true, removedState)"));
        for(var name:new String[]{"DualCabinetBlock.java","DualCabinetPartBlock.java"})
            assertTrue(Files.readString(dir.resolve(name)).contains("DualCabinetStructure.removed(level, pos, state)"));
    }
}
