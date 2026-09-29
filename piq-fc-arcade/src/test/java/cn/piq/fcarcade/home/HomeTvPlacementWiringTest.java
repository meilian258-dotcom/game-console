package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Production placement wiring plus pure mixed-layout regressions; no Minecraft registry bootstrap. */
class HomeTvPlacementWiringTest {
    @Test void newPlacementExplicitlyUsesEightCellsWithoutRemovingPersistedLayoutProperty() throws Exception {
        String source = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/RetroTvBlock.java"));
        String placement = source.substring(source.indexOf("getStateForPlacement(BlockPlaceContext"),
                source.indexOf("@Override public BlockEntity newBlockEntity"));
        assertTrue(placement.contains("super.getStateForPlacement(context)"));
        assertTrue(placement.contains("state.setValue(CENTERED, false)"));
        assertFalse(placement.contains("setValue(CENTERED, true)"));
        assertTrue(placement.contains("HomeTvStructure.canPlace(context, state)"));
        assertTrue(source.contains("BooleanProperty.create(\"centered\")"));
        assertTrue(source.contains("builder.add(CENTERED, LIT)"));
        assertTrue(source.contains("BooleanProperty.create(\"lit\")"));
        assertTrue(source.contains("state.getValue(LIT) ? 10 : 0"));
        assertTrue(source.contains("setValue(CENTERED, false).setValue(LIT, false)"));
    }

    @Test void newEightCellLayoutStaysTwoWideHighAndDeepAndChecksEveryCell() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var cells = HomeTvFootprint.cells(facing, false);
            assertEquals(8, cells.size());
            assertEquals(8, new HashSet<>(cells).size());
            assertEquals(new HomeTvFootprint.Cell(0, 0, 0, 0), cells.getFirst());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::x).distinct().count());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::y).distinct().count());
            assertEquals(2, cells.stream().map(HomeTvFootprint.Cell::z).distinct().count());
            assertTrue(HomeTvFootprint.canPlace(facing, false, cell -> true));
            for (var blocked : cells)
                assertFalse(HomeTvFootprint.canPlace(facing, false, cell -> cell.part() != blocked.part()));
        }
    }

    @Test void newEightCellTvCanBeRemovedWithoutMovingOrTruncatingSavedTwelveCellTv() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var old = new HomeTvAssemblyLedger.Assembly(new UUID(0, 1), 15, 63, 15, facing, false, 0, true);
            var fresh = new HomeTvAssemblyLedger.Assembly(new UUID(0, 2), 31, 63, 31, facing, false, 0, false);
            assertTrue(ledger.restore(old));
            assertTrue(ledger.restore(fresh));
            assertTrue(ledger.close(fresh.id(), 0, fresh.x(), fresh.y(), fresh.z()));
            for (int part = 0; part < 8; part++) ledger.acknowledge(fresh.id(), part);
            assertNull(ledger.get(fresh.id()));
            assertEquals(old, ledger.get(old.id()));
            var reloaded = new HomeTvAssemblyLedger();
            for (var saved : ledger.snapshots()) assertTrue(reloaded.restore(saved));
            assertEquals(old, reloaded.get(old.id()));
            var last = HomeTvFootprint.cell(facing, 11, true);
            assertTrue(reloaded.close(old.id(), 11, old.x() + last.x(), old.y() + last.y(), old.z() + last.z()));
            for (int part = 0; part < 8; part++) reloaded.acknowledge(old.id(), part);
            assertNotNull(reloaded.get(old.id()));
            assertTrue(reloaded.get(old.id()).centered());
            for (int part = 8; part < 12; part++) reloaded.acknowledge(old.id(), part);
            assertNull(reloaded.get(old.id()));
        }
    }
}
