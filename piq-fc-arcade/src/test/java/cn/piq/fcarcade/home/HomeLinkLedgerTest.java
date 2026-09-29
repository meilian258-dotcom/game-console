package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomeLinkLedgerTest {
    private static HomeLinkLedger.Endpoint console(int x) {
        return new HomeLinkLedger.Endpoint(UUID.randomUUID(), "minecraft:overworld", x, 64, 0,
                HomeLinkLedger.Kind.CONSOLE);
    }
    private static HomeLinkLedger.Endpoint tv(int x) {
        return new HomeLinkLedger.Endpoint(UUID.randomUUID(), "minecraft:overworld", x, 64, 0,
                HomeLinkLedger.Kind.TV);
    }

    @Test
    void connectUnloadedRemoteDisconnectReloadReturnsOnlyOnePaidCable() {
        HomeLinkLedger original = new HomeLinkLedger();
        var console = console(15);
        var tv = tv(16); // Adjacent chunks may load and unload independently.
        var link = original.connect(console, tv);
        assertNotNull(link);
        HomeLinkLedger reloaded = reload(original); // Unload/reload is not a disconnect.
        assertFalse(reloaded.get(link.id()).closed());
        var close = reloaded.close(link.id(), tv);
        assertTrue(close.refundCable());
        assertTrue(reloaded.acknowledgeCleared(link.id(), tv));
        assertNotNull(reloaded.get(link.id())); // The unloaded console still has its old link NBT.
        HomeLinkLedger afterRestart = reload(reloaded);
        assertFalse(afterRestart.close(link.id(), console).refundCable());
        assertTrue(afterRestart.acknowledgeCleared(link.id(), console));
        assertNull(afterRestart.get(link.id()));
        assertNull(afterRestart.close(link.id(), console));
    }

    @Test
    void bothRemovalCallbacksAndRepeatedClicksDoNotDuplicateRefunds() {
        var ledger = new HomeLinkLedger();
        var console = console(0);
        var tv = tv(1);
        var link = ledger.connect(console, tv);
        int refunds = 0;
        for (var endpoint : new HomeLinkLedger.Endpoint[]{console, console, tv, tv})
            if (ledger.close(link.id(), endpoint).refundCable()) refunds++;
        assertEquals(1, refunds);
        ledger.acknowledgeCleared(link.id(), console);
        ledger.acknowledgeCleared(link.id(), tv);
        assertTrue(ledger.snapshots().isEmpty());
    }

    @Test
    void sameCoordinateReplacementCannotOwnOrRefundOldWire() {
        var ledger = new HomeLinkLedger();
        var original = console(0);
        var television = tv(2);
        var link = ledger.connect(original, television);
        var replacement = console(0);
        assertFalse(link.owns(replacement));
        assertNull(ledger.close(link.id(), replacement));
        assertNull(link.other(replacement));
        assertTrue(ledger.close(link.id(), television).refundCable());
        ledger.acknowledgeCleared(link.id(), original);
        ledger.acknowledgeCleared(link.id(), television);
        var fresh = ledger.connect(replacement, television);
        assertNotNull(fresh);
        assertNotEquals(link.id(), fresh.id());
    }

    @Test
    void oldTombstoneCannotReleaseANewerLinkOnTheSameSurvivingEndpoint() {
        var ledger = new HomeLinkLedger();
        var oldConsole = console(0);
        var television = tv(2);
        var old = ledger.connect(oldConsole, television);
        assertTrue(ledger.close(old.id(), television).refundCable());
        ledger.acknowledgeCleared(old.id(), television);
        var next = ledger.connect(console(1), television);
        assertNotNull(next);
        assertFalse(ledger.close(old.id(), oldConsole).refundCable());
        ledger.acknowledgeCleared(old.id(), oldConsole);
        assertFalse(ledger.get(next.id()).closed());
        assertNull(ledger.connect(console(3), television));
    }

    @Test
    void oneToOneWiringRequiresExplicitDisconnectBeforeRebinding() {
        var ledger = new HomeLinkLedger();
        var console = console(0);
        var television = tv(1);
        var link = ledger.connect(console, television);
        assertNull(ledger.connect(console, tv(2)));
        assertNull(ledger.connect(console(2), television));
        ledger.close(link.id(), console);
        assertNotNull(ledger.connect(console, tv(2)));
    }

    @Test
    void validatesDimensionRolesAndEuclideanCableLengthWithoutWorldAccess() {
        var console = console(0);
        assertTrue(HomeLinkLedger.inRange(console, tv(8)));
        assertFalse(HomeLinkLedger.inRange(console, tv(9)));
        var diagonal = new HomeLinkLedger.Endpoint(UUID.randomUUID(), "minecraft:overworld", 6, 64, 6,
                HomeLinkLedger.Kind.TV);
        assertFalse(HomeLinkLedger.inRange(console, diagonal));
        var otherDimension = new HomeLinkLedger.Endpoint(UUID.randomUUID(), "minecraft:the_nether", 0, 64, 0,
                HomeLinkLedger.Kind.TV);
        assertFalse(HomeLinkLedger.inRange(console, otherDimension));
        assertFalse(HomeLinkLedger.inRange(console, console(1)));
        assertFalse(HomeLinkLedger.inRange(tv(0), console));
    }

    @Test
    void restoredIdentityConflictAndUnknownLinksNeverAuthorizeRefunds() {
        var ledger = new HomeLinkLedger();
        var console = console(0);
        var television = tv(1);
        var link = ledger.connect(console, television);
        var conflict = new HomeLinkLedger.Link(UUID.randomUUID(), console, tv(2), false, false, false, false);
        assertFalse(ledger.restore(conflict));
        assertFalse(ledger.restore(link));
        assertNull(ledger.close(UUID.randomUUID(), console));
        var relocatedClone = new HomeLinkLedger.Endpoint(console.id(), console.dimension(), 3, 64, 0,
                HomeLinkLedger.Kind.CONSOLE);
        assertFalse(link.owns(relocatedClone));
        assertNull(ledger.close(link.id(), relocatedClone));
    }

    private static HomeLinkLedger reload(HomeLinkLedger before) {
        var after = new HomeLinkLedger();
        for (var snapshot : before.snapshots()) assertTrue(after.restore(snapshot));
        return after;
    }
}
