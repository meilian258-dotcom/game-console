package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HomeTvAssemblyLedgerTest {
    private static HomeTvAssemblyLedger.Assembly tv(UUID id, HomeTvFootprint.Facing facing) {
        return new HomeTvAssemblyLedger.Assembly(id, 15, 63, 15, facing, false, 0);
    }

    @Test void destroyingEveryPartAndRepeatedCallbacksClaimsOneTvOnly() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var id = UUID.randomUUID();
            assertTrue(ledger.restore(tv(id, facing)));
            int claims = 0;
            for (int pass = 0; pass < 2; pass++) for (var cell : HomeTvFootprint.cells(facing))
                if (ledger.close(id, cell.part(), 15 + cell.x(), 63 + cell.y(), 15 + cell.z())) claims++;
            assertEquals(1, claims);
        }
    }

    @Test void unloadedPartsKeepClosedClaimAcrossReloadUntilTheyAcknowledge() {
        var ledger = new HomeTvAssemblyLedger(); var id = UUID.randomUUID();
        ledger.restore(tv(id, HomeTvFootprint.Facing.NORTH));
        assertTrue(ledger.close(id, 0, 15, 63, 15));
        for (int part = 0; part < 4; part++) ledger.acknowledge(id, part);
        var reloaded = new HomeTvAssemblyLedger();
        for (var saved : ledger.snapshots()) assertTrue(reloaded.restore(saved));
        assertTrue(reloaded.get(id).closed());
        assertFalse(reloaded.close(id, 7, 16, 64, 16));
        for (int part = 4; part < 8; part++) reloaded.acknowledge(id, part);
        assertNull(reloaded.get(id));
    }

    @Test void foreignCoordinatesOrIdentityCannotCloseTheOriginalOrReplacement() {
        var ledger = new HomeTvAssemblyLedger(); var old = UUID.randomUUID(); var replacement = UUID.randomUUID();
        ledger.restore(tv(old, HomeTvFootprint.Facing.EAST));
        ledger.restore(tv(replacement, HomeTvFootprint.Facing.EAST));
        assertFalse(ledger.close(old, 0, 16, 63, 15));
        assertFalse(ledger.close(UUID.randomUUID(), 0, 15, 63, 15));
        assertTrue(ledger.close(old, 7, 14, 64, 16));
        for (int part = 0; part < 8; part++) ledger.acknowledge(old, part);
        assertNotNull(ledger.get(replacement));
        assertFalse(ledger.get(replacement).closed());
    }

    @Test void cancelledMultiPlaceReturnsNoDropAndLeavesNoPersistentLedgerEntry() {
        var ledger = new HomeTvAssemblyLedger(); var id = UUID.randomUUID();
        ledger.restore(tv(id, HomeTvFootprint.Facing.NORTH)); ledger.awaitPlacementEvent(id);
        assertFalse(ledger.close(id, 0, 15, 63, 15));
        assertFalse(ledger.cancelPlacement(id, 7, 100, 64, 16));
        // NeoForge restores reverse order: the first owned proxy discards the
        // provisional record; the other seven callbacks are harmless.
        for (int part = 7; part >= 0; part--) {
            var cell = HomeTvFootprint.cell(HomeTvFootprint.Facing.NORTH, part);
            assertEquals(part == 7, ledger.cancelPlacement(id, part, 15 + cell.x(), 63 + cell.y(), 15 + cell.z()));
        }
        assertTrue(ledger.snapshots().isEmpty());
    }

    @Test void acceptedPlacementCannotBeCancelledByLaterUnrelatedRestoration() {
        var ledger = new HomeTvAssemblyLedger(); var id = UUID.randomUUID();
        ledger.restore(tv(id, HomeTvFootprint.Facing.NORTH)); ledger.awaitPlacementEvent(id);
        ledger.confirmPlacement(id);
        assertFalse(ledger.cancelPlacement(id, 0, 15, 63, 15));
        assertTrue(ledger.close(id, 0, 15, 63, 15));
    }
}
