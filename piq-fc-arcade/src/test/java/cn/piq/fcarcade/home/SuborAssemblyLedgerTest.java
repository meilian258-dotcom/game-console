package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SuborAssemblyLedgerTest {
    @Test void anyPartCanClaimOneDropAndReloadedLateCleanupCannotClaimItAgain() {
        for (var facing : SuborFootprint.Facing.values()) for (int first = 0; first < 4; first++) {
            var ledger = new SuborAssemblyLedger(); UUID id = UUID.randomUUID();
            assertTrue(ledger.restore(new SuborAssemblyLedger.Assembly(id, 15, 64, 15, facing, false, 0)));
            var cell = SuborFootprint.cell(facing, first);
            assertTrue(ledger.close(id, first, 15 + cell.x(), 64, 15 + cell.z()));
            for (var part : SuborFootprint.cells(facing)) {
                var loaded = new SuborAssemblyLedger();
                for (var entry : ledger.snapshots()) assertTrue(loaded.restore(entry));
                ledger = loaded;
                assertFalse(ledger.close(id, part.part(), 15 + part.x(), 64, 15 + part.z()));
                ledger.acknowledge(id, part.part());
            }
            assertNull(ledger.get(id));
        }
    }

    @Test void cancelledPlacementRequiresExactOwnerAndCellAndNeverCreatesAnItemClaim() {
        var ledger = new SuborAssemblyLedger(); UUID id = UUID.randomUUID();
        ledger.restore(new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, false, 0));
        ledger.awaitPlacementEvent(id);
        assertFalse(ledger.close(id, 0, 0, 0, 0));
        assertFalse(ledger.cancelPlacement(UUID.randomUUID(), 0, 0, 0, 0));
        assertFalse(ledger.cancelPlacement(id, 3, 0, 0, 0));
        assertTrue(ledger.cancelPlacement(id, 3, 1, 0, 1));
        assertNull(ledger.get(id)); assertFalse(ledger.close(id, 0, 0, 0, 0));
    }

    @Test void pendingConfirmationAndOldUuidCannotTouchNewMachineAtSameCoordinates() {
        var ledger = new SuborAssemblyLedger(); UUID old = UUID.randomUUID(), fresh = UUID.randomUUID();
        ledger.restore(new SuborAssemblyLedger.Assembly(old, 0, 0, 0, SuborFootprint.Facing.NORTH, false, 0));
        ledger.awaitPlacementEvent(old);
        ledger.restore(new SuborAssemblyLedger.Assembly(fresh, 0, 0, 0, SuborFootprint.Facing.EAST, false, 0));
        ledger.awaitPlacementEvent(fresh); ledger.cancelPlacement(old, 0, 0, 0, 0);
        assertTrue(ledger.pending(fresh)); ledger.confirmPlacement(fresh);
        assertTrue(ledger.close(fresh, 0, 0, 0, 0));
    }

    @Test void invalidMasksDuplicatesAndOffStructurePositionsNeverAuthorizeDrops() {
        var ledger = new SuborAssemblyLedger(); UUID id = UUID.randomUUID();
        assertFalse(ledger.restore(new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, false, 1)));
        assertFalse(ledger.restore(new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, true, 16)));
        var entry = new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, false, 0);
        assertTrue(ledger.restore(entry)); assertFalse(ledger.restore(entry));
        assertFalse(ledger.close(id, 4, 0, 0, 0)); assertFalse(ledger.close(id, 0, 0, 1, 0));
        assertFalse(ledger.close(id, 1, 100, 0, 0)); assertFalse(ledger.get(id).closed());
    }

    @Test void compactClaimsExactlyOnceAndFinishesAfterTwoCellsAcrossReloads() {
        for (var facing : SuborFootprint.Facing.values()) for (int first = 0; first < 2; first++) {
            var ledger = new SuborAssemblyLedger(); var id = UUID.randomUUID();
            assertTrue(ledger.restore(new SuborAssemblyLedger.Assembly(id, 15, 64, 15, facing, true, false, 0)));
            var clicked = SuborFootprint.cell(facing, first, true);
            assertTrue(ledger.close(id, first, 15 + clicked.x(), 64, 15 + clicked.z()));
            for (var part : SuborFootprint.cells(facing, true)) {
                var reloaded = new SuborAssemblyLedger();
                for (var entry : ledger.snapshots()) assertTrue(reloaded.restore(entry));
                ledger = reloaded;
                assertTrue(ledger.get(id).compact());
                assertFalse(ledger.close(id, part.part(), 15 + part.x(), 64, 15 + part.z()));
                ledger.acknowledge(id, part.part());
            }
            assertNull(ledger.get(id));
        }
    }

    @Test void compactNeverOwnsRearCellsAndRejectsLegacyCleanupBits() {
        var ledger = new SuborAssemblyLedger(); var id = UUID.randomUUID();
        var entry = new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, true, false, 0);
        assertTrue(ledger.restore(entry));
        assertFalse(entry.owns(2, 0, 0, 1)); assertFalse(entry.owns(3, 1, 0, 1));
        assertFalse(ledger.close(id, 2, 0, 0, 1));
        assertFalse(ledger.restore(new SuborAssemblyLedger.Assembly(UUID.randomUUID(), 0, 0, 0,
                SuborFootprint.Facing.NORTH, true, true, 4)));
        assertTrue(ledger.close(id, 0, 0, 0, 0)); ledger.acknowledge(id, 3);
        assertEquals(0, ledger.get(id).cleared());
        ledger.acknowledge(id, 0); assertEquals(1, ledger.get(id).cleared());
        ledger.acknowledge(id, 1); assertNull(ledger.get(id));
    }

    @Test void legacyConstructorPreservesFourCellOwnershipAndDoesNotAutoCompactOnRestore() {
        var id = UUID.randomUUID(); var ledger = new SuborAssemblyLedger();
        var legacy = new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, true, 3);
        assertFalse(legacy.compact()); assertEquals(15, legacy.completeMask());
        assertTrue(legacy.owns(2, 0, 0, 1)); assertTrue(legacy.owns(3, 1, 0, 1));
        assertTrue(ledger.restore(legacy)); assertNotNull(ledger.get(id));
        ledger.acknowledge(id, 2); assertNotNull(ledger.get(id));
        ledger.acknowledge(id, 3); assertNull(ledger.get(id));
    }
}
