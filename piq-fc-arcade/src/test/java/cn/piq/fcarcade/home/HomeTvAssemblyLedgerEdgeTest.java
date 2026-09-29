package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Plain-JVM boundary checks supplementing the lifecycle happy-path tests. */
class HomeTvAssemblyLedgerEdgeTest {
    private static final UUID ID = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    private static HomeTvAssemblyLedger.Assembly tv(UUID id, HomeTvFootprint.Facing facing) {
        return new HomeTvAssemblyLedger.Assembly(id, 15, 63, 15, facing, false, 0);
    }

    private static boolean close(HomeTvAssemblyLedger ledger, HomeTvAssemblyLedger.Assembly tv, int part) {
        var cell = HomeTvFootprint.cell(tv.facing(), part);
        return ledger.close(tv.id(), part, tv.x() + cell.x(), tv.y() + cell.y(), tv.z() + cell.z());
    }

    private static boolean cancel(HomeTvAssemblyLedger ledger, HomeTvAssemblyLedger.Assembly tv, int part) {
        var cell = HomeTvFootprint.cell(tv.facing(), part);
        return ledger.cancelPlacement(tv.id(), part, tv.x() + cell.x(), tv.y() + cell.y(), tv.z() + cell.z());
    }

    private static HomeTvAssemblyLedger reload(HomeTvAssemblyLedger before) {
        var after = new HomeTvAssemblyLedger();
        for (var snapshot : before.snapshots()) assertTrue(after.restore(snapshot));
        return after;
    }

    @Test void eachOfEightPartsCanBeTheFirstAndOnlyClaimInEveryFacing() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int first = 0; first < 8; first++) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing);
            assertTrue(ledger.restore(assembly));
            assertTrue(close(ledger, assembly, first), facing + " first part " + first);
            for (int part = 7; part >= 0; part--) assertFalse(close(ledger, assembly, part));
            assertTrue(ledger.get(ID).closed());
            assertEquals(0, ledger.get(ID).cleared());
        }
    }

    @Test void everyIndividualAcknowledgementSurvivesReloadWithoutAnotherClaim() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing);
            assertTrue(ledger.restore(assembly));
            assertTrue(close(ledger, assembly, 7));
            int expectedMask = 0;
            for (int part : new int[]{7, 3, 5, 1, 6, 0, 4, 2}) {
                ledger.acknowledge(ID, part);
                ledger.acknowledge(ID, part); // Duplicate callbacks cannot acknowledge another cell.
                expectedMask |= 1 << part;
                ledger = reload(ledger);
                if (expectedMask == 255) {
                    assertNull(ledger.get(ID));
                    assertTrue(ledger.snapshots().isEmpty());
                } else {
                    assertTrue(ledger.get(ID).closed());
                    assertEquals(expectedMask, ledger.get(ID).cleared());
                }
                for (int retry = 0; retry < 8; retry++) assertFalse(close(ledger, assembly, retry));
            }
        }
    }

    @Test void restoreRejectsMalformedMasksAndDuplicateIdentityWithoutChangingTheOwner() {
        var ledger = new HomeTvAssemblyLedger();
        var north = HomeTvFootprint.Facing.NORTH;
        assertFalse(ledger.restore(null));
        assertFalse(ledger.restore(tv(null, north)));
        assertFalse(ledger.restore(tv(ID, null)));
        for (int mask : new int[]{-1, 256, Integer.MIN_VALUE})
            assertFalse(ledger.restore(new HomeTvAssemblyLedger.Assembly(ID, 15, 63, 15, north, true, mask)));
        for (int mask : new int[]{1, 127, 255})
            assertFalse(ledger.restore(new HomeTvAssemblyLedger.Assembly(ID, 15, 63, 15, north, false, mask)));
        assertTrue(ledger.snapshots().isEmpty());

        var original = tv(ID, north);
        assertTrue(ledger.restore(original));
        assertFalse(ledger.restore(original));
        assertFalse(ledger.restore(new HomeTvAssemblyLedger.Assembly(ID, 999, 63, 15,
                HomeTvFootprint.Facing.WEST, true, 1)));
        assertEquals(original, ledger.get(ID));
        assertEquals(1, ledger.snapshots().size());

        var alreadyCleared = new HomeTvAssemblyLedger.Assembly(OTHER, 15, 63, 15, north, true, 255);
        assertTrue(ledger.restore(alreadyCleared)); // Completed tombstones are intentionally discarded.
        assertNull(ledger.get(OTHER));
        assertEquals(original, ledger.get(ID));
    }

    @Test void pendingPlacementBlocksEveryPartAndUnknownConfirmationCannotReleaseIt() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing);
            ledger.awaitPlacementEvent(OTHER);
            assertFalse(ledger.pending(OTHER));
            assertTrue(ledger.restore(assembly));
            ledger.awaitPlacementEvent(ID);
            ledger.awaitPlacementEvent(ID);
            ledger.confirmPlacement(OTHER);
            for (int part = 0; part < 8; part++) assertFalse(close(ledger, assembly, part));
            assertTrue(ledger.pending(ID));
            assertEquals(assembly, ledger.get(ID));
        }
    }

    @Test void onlyCorrectPendingOwnerAndCoordinatesCanCancelAndEveryPartCanStartRollback() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int first = 0; first < 8; first++) {
            var ledger = new HomeTvAssemblyLedger();
            var assembly = tv(ID, facing);
            assertTrue(ledger.restore(assembly));
            ledger.awaitPlacementEvent(ID);
            assertFalse(ledger.cancelPlacement(OTHER, 0, 15, 63, 15));
            assertFalse(ledger.cancelPlacement(ID, -1, 15, 63, 15));
            assertFalse(ledger.cancelPlacement(ID, 8, 15, 63, 15));
            assertFalse(ledger.cancelPlacement(ID, 0, 16, 63, 15));
            assertFalse(ledger.cancelPlacement(ID, 0, 15, 64, 15));
            assertFalse(ledger.cancelPlacement(ID, 0, 15, 63, 16));
            assertTrue(ledger.pending(ID));
            assertEquals(assembly, ledger.get(ID));
            assertTrue(cancel(ledger, assembly, first));
            assertFalse(ledger.pending(ID));
            assertNull(ledger.get(ID));
            assertTrue(reload(ledger).snapshots().isEmpty());
            for (int part = 0; part < 8; part++) {
                assertFalse(cancel(ledger, assembly, part));
                assertFalse(close(ledger, assembly, part));
            }
        }
    }

    @Test void confirmingOnePlacementDoesNotConfirmOrCancelAnotherIdentity() {
        var ledger = new HomeTvAssemblyLedger();
        var first = tv(ID, HomeTvFootprint.Facing.NORTH);
        var second = tv(OTHER, HomeTvFootprint.Facing.NORTH);
        assertTrue(ledger.restore(first));
        assertTrue(ledger.restore(second));
        ledger.awaitPlacementEvent(ID);
        ledger.awaitPlacementEvent(OTHER);
        ledger.confirmPlacement(ID);
        ledger.confirmPlacement(ID);
        assertFalse(ledger.pending(ID));
        assertTrue(ledger.pending(OTHER));
        assertFalse(cancel(ledger, first, 0));
        assertTrue(close(ledger, first, 6));
        assertFalse(close(ledger, first, 0));
        assertFalse(close(ledger, second, 0));
        assertTrue(cancel(ledger, second, 7));
        assertTrue(ledger.get(ID).closed());
    }

    @Test void cancelledOldIdentityCannotAlterNewPlacementAtExactlyTheSameCoordinates() {
        var ledger = new HomeTvAssemblyLedger();
        var old = tv(ID, HomeTvFootprint.Facing.WEST);
        var replacement = tv(OTHER, HomeTvFootprint.Facing.WEST);
        assertTrue(ledger.restore(old));
        ledger.awaitPlacementEvent(ID);
        assertTrue(cancel(ledger, old, 7));
        assertTrue(ledger.restore(replacement));
        ledger.awaitPlacementEvent(OTHER);
        ledger.awaitPlacementEvent(ID);
        ledger.confirmPlacement(ID);
        for (int part = 0; part < 8; part++) {
            assertFalse(cancel(ledger, old, part));
            assertFalse(close(ledger, old, part));
            ledger.acknowledge(ID, part);
        }
        assertFalse(ledger.pending(ID));
        assertTrue(ledger.pending(OTHER));
        assertEquals(replacement, ledger.get(OTHER));
        assertEquals(1, ledger.snapshots().size());
        ledger.confirmPlacement(OTHER);
        assertTrue(close(ledger, replacement, 7));
    }

    @Test void invalidOrPrematureAcknowledgementsNeverConsumeLiveCells() {
        var ledger = new HomeTvAssemblyLedger();
        var assembly = tv(ID, HomeTvFootprint.Facing.SOUTH);
        assertTrue(ledger.restore(assembly));
        for (int part = -1; part <= 8; part++) ledger.acknowledge(ID, part);
        assertEquals(assembly, ledger.get(ID));
        assertFalse(ledger.close(ID, -1, 15, 63, 15));
        assertFalse(ledger.close(ID, 8, 15, 63, 15));
        assertFalse(ledger.close(ID, 0, 16, 63, 15));
        assertFalse(ledger.close(ID, 0, 15, 64, 15));
        assertFalse(ledger.close(ID, 0, 15, 63, 16));
        assertTrue(close(ledger, assembly, 4));
        ledger.acknowledge(OTHER, 0);
        ledger.acknowledge(ID, -1);
        ledger.acknowledge(ID, 8);
        assertEquals(0, ledger.get(ID).cleared());
    }
}
