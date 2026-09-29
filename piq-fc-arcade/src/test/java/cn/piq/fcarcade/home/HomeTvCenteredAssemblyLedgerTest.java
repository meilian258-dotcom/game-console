package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomeTvCenteredAssemblyLedgerTest {
    private static final UUID ID = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    private static HomeTvAssemblyLedger.Assembly tv(UUID id, HomeTvFootprint.Facing facing, boolean centered) {
        return new HomeTvAssemblyLedger.Assembly(id, 15, 63, 15, facing, false, 0, centered);
    }

    private static boolean close(HomeTvAssemblyLedger ledger, HomeTvAssemblyLedger.Assembly tv, int part) {
        var cell = HomeTvFootprint.cell(tv.facing(), part, tv.centered());
        return ledger.close(tv.id(), part, tv.x() + cell.x(), tv.y() + cell.y(), tv.z() + cell.z());
    }

    private static HomeTvAssemblyLedger reload(HomeTvAssemblyLedger before) {
        var after = new HomeTvAssemblyLedger();
        for (var saved : before.snapshots()) assertTrue(after.restore(saved));
        return after;
    }

    @Test void oldConstructorKeepsLegacyOwnershipWithoutReinterpretingPartNumbers() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var legacy = new HomeTvAssemblyLedger.Assembly(ID, 15, 63, 15, facing, false, 0);
            assertFalse(legacy.centered());
            assertEquals(tv(ID, facing, false), legacy);
            for (var cell : HomeTvFootprint.cells(facing))
                assertTrue(legacy.owns(cell.part(), 15 + cell.x(), 63 + cell.y(), 15 + cell.z()));
            assertFalse(legacy.owns(8, 15, 63, 15));
        }
    }

    @Test void eachCenteredPartCanClaimOnlyOneDropInEveryFacing() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int first = 0; first < 12; first++) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing, true);
            assertTrue(ledger.restore(assembly));
            assertTrue(close(ledger, assembly, first));
            assertTrue(ledger.get(ID).centered());
            for (int part = 11; part >= 0; part--) assertFalse(close(ledger, assembly, part));
        }
    }

    @Test void clearingEightPartsDoesNotDiscardFourUnloadedCenteredPartsAcrossReload() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing, true);
            assertTrue(ledger.restore(assembly));
            assertTrue(close(ledger, assembly, 11));
            for (int part = 0; part < 12; part++) {
                ledger.acknowledge(ID, part);
                ledger.acknowledge(ID, part);
                ledger = reload(ledger);
                if (part == 11) assertNull(ledger.get(ID));
                else {
                    assertTrue(ledger.get(ID).centered());
                    assertTrue(ledger.get(ID).closed());
                    assertEquals((1 << (part + 1)) - 1, ledger.get(ID).cleared());
                }
                assertFalse(close(ledger, assembly, 11));
            }
            assertTrue(ledger.snapshots().isEmpty());
        }
    }

    @Test void masksAreValidatedAgainstThePersistedLayout() {
        var facing = HomeTvFootprint.Facing.NORTH;
        var legacy = new HomeTvAssemblyLedger();
        assertFalse(legacy.restore(new HomeTvAssemblyLedger.Assembly(ID, 0, 0, 0, facing, true, 256)));
        var centered = new HomeTvAssemblyLedger();
        assertTrue(centered.restore(new HomeTvAssemblyLedger.Assembly(ID, 0, 0, 0, facing, true, 255, true)));
        assertNotNull(centered.get(ID));
        assertEquals(255, centered.get(ID).cleared());
        for (int bad : new int[]{-1, 4096, Integer.MIN_VALUE})
            assertFalse(centered.restore(new HomeTvAssemblyLedger.Assembly(OTHER, 0, 0, 0, facing, true, bad, true)));
        for (int bad : new int[]{1, 255, 4095})
            assertFalse(centered.restore(new HomeTvAssemblyLedger.Assembly(OTHER, 0, 0, 0, facing, false, bad, true)));
        assertTrue(centered.restore(new HomeTvAssemblyLedger.Assembly(OTHER, 0, 0, 0, facing, true, 4095, true)));
        assertNull(centered.get(OTHER));
    }

    @Test void centeredPartCoordinatesCannotAuthorizeLegacyOwnershipOrForeignIdentity() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing, true);
            var replacement = tv(OTHER, facing, false);
            assertTrue(ledger.restore(assembly));
            assertTrue(ledger.restore(replacement));
            var oldPart = HomeTvFootprint.cell(facing, 1);
            assertFalse(ledger.close(ID, 1, 15 + oldPart.x(), 63 + oldPart.y(), 15 + oldPart.z()));
            var newPart = HomeTvFootprint.cell(facing, 1, true);
            assertFalse(ledger.close(OTHER, 1, 15 + newPart.x(), 63 + newPart.y(), 15 + newPart.z()));
            assertFalse(ledger.close(new UUID(0, 3), 0, 15, 63, 15));
            assertTrue(close(ledger, assembly, 1));
            for (int part = 0; part < 12; part++) ledger.acknowledge(ID, part);
            assertEquals(replacement, ledger.get(OTHER));
        }
    }

    @Test void everyCenteredPartCanCancelPendingPlacementWithoutDropsOrAffectingReplacement() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int first = 0; first < 12; first++) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing, true);
            assertTrue(ledger.restore(assembly));
            ledger.awaitPlacementEvent(ID);
            for (int part = 0; part < 12; part++) assertFalse(close(ledger, assembly, part));
            var cell = HomeTvFootprint.cell(facing, first, true);
            assertTrue(ledger.cancelPlacement(ID, first, 15 + cell.x(), 63 + cell.y(), 15 + cell.z()));
            assertFalse(ledger.pending(ID));
            assertNull(ledger.get(ID));
            var replacement = tv(OTHER, facing, true);
            assertTrue(ledger.restore(replacement));
            assertFalse(ledger.cancelPlacement(ID, first, 15 + cell.x(), 63 + cell.y(), 15 + cell.z()));
            for (int part = 0; part < 12; part++) { assertFalse(close(ledger, assembly, part)); ledger.acknowledge(ID, part); }
            assertEquals(replacement, ledger.get(OTHER));
        }
    }

    @Test void invalidAcknowledgementsCannotClearLiveOrNonexistentCenteredCells() {
        var ledger = new HomeTvAssemblyLedger();
        var assembly = tv(ID, HomeTvFootprint.Facing.SOUTH, true);
        assertTrue(ledger.restore(assembly));
        for (int part = -1; part <= 12; part++) ledger.acknowledge(ID, part);
        assertEquals(assembly, ledger.get(ID));
        assertFalse(ledger.close(ID, -1, 15, 63, 15));
        assertFalse(ledger.close(ID, 12, 15, 63, 15));
        assertTrue(close(ledger, assembly, 10));
        ledger.acknowledge(ID, -1);
        ledger.acknowledge(ID, 12);
        ledger.acknowledge(OTHER, 10);
        assertEquals(0, ledger.get(ID).cleared());
        assertTrue(ledger.get(ID).centered());
    }
}
