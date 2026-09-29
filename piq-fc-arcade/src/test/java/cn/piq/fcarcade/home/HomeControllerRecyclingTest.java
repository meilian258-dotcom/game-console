package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerRecyclingTest {
    private static final HomeControllerLedger.Console CONSOLE = new HomeControllerLedger.Console(
            UUID.randomUUID(), "minecraft:overworld", 2, 64, 3);
    private static final class PhysicalItem {
        final UUID token;
        final boolean borrowed;
        int count;
        PhysicalItem(UUID token, boolean borrowed, int count) { this.token = token; this.borrowed = borrowed; this.count = count; }
        PhysicalItem copy() { return new PhysicalItem(token, borrowed, count); }
    }
    private static int recycle(List<PhysicalItem> places, UUID token) {
        return HomeControllerInventory.recycle(places, item -> item.borrowed && token.equals(item.token),
                item -> item.count, item -> item.count = 0);
    }
    @Test void oneHundredDropAndRetakeCyclesOnEitherPortCannotAccumulatePhysicalItems() {
      for (int port = 0; port < 2; port++) {
        var ledger = new HomeControllerLedger(); var owner = UUID.randomUUID(); var world = new ArrayList<PhysicalItem>();
        for (int cycle = 1; cycle <= 100; cycle++) {
            var lease = ledger.issue(CONSOLE, cycle, port, owner); assertNotNull(lease);
            var inventory = new PhysicalItem(lease.id(), true, 1);
            var dropped = inventory.copy(); inventory.count = 0; // Q splits before ItemTossEvent.
            assertTrue(HomeControllerInventory.removedForToss(
                    HomeControllerInventory.locate(lease.id(), List.of(inventory), item -> item.token, item -> item.count), null, dropped));
            assertFalse(HomeControllerInventory.keepDropped(lease, UUID.randomUUID(), dropped.count, cycle));
            assertEquals(HomeControllerInventory.TossAction.RETURN_LOAN,
                    HomeControllerInventory.tossAction(lease, owner, true, true, cycle));
            ledger.revokeSession(cycle);
            assertEquals(1, recycle(List.of(inventory, dropped), lease.id()));
            world.add(dropped);
            assertEquals(0, world.stream().mapToInt(item -> item.count).sum());
            assertTrue(ledger.snapshots().isEmpty());
        }
      }
    }
    @Test void guiOutsideDropConsumesItsCursorAliasExactlyOnceBeforeVanillaClearsIt() {
        var token = UUID.randomUUID(); var cursorAndEntity = new PhysicalItem(token, true, 1);
        var located = HomeControllerInventory.locate(token, List.of(cursorAndEntity), item -> item.token, item -> item.count);
        assertTrue(HomeControllerInventory.removedForToss(located, cursorAndEntity, cursorAndEntity));
        assertEquals(1, recycle(List.of(cursorAndEntity, cursorAndEntity), token));
        assertEquals(0, cursorAndEntity.count);
        assertEquals(0, recycle(List.of(cursorAndEntity), token)); // Cancel never restores or issues another object.
    }
    @Test void sessionCloseConsumesMovedCopiesInInventoryCursorAndVisibleContainerOnly() {
        var token = UUID.randomUUID(); var oldSlot = new PhysicalItem(token, true, 0);
        var inventoryCopy = new PhysicalItem(token, true, 1); var cursorCopy = inventoryCopy.copy(); var chestCopy = inventoryCopy.copy();
        var anotherLease = new PhysicalItem(UUID.randomUUID(), true, 1);
        var ordinaryItem = new PhysicalItem(token, false, 64);
        assertEquals(3, recycle(List.of(oldSlot, inventoryCopy, cursorCopy, chestCopy, chestCopy, anotherLease, ordinaryItem), token));
        assertEquals(0, inventoryCopy.count + cursorCopy.count + chestCopy.count);
        assertEquals(1, anotherLease.count); assertEquals(64, ordinaryItem.count);
    }
    @Test void p2ActualCarrierSurvivesUntilPickupAndApprovalButNotAfterTimeout() {
        var ledger = new HomeControllerLedger(); var owner = UUID.randomUUID(); var receiver = UUID.randomUUID(); var carrier = UUID.randomUUID();
        var lease = ledger.issue(CONSOLE, 1, 1, owner);
        var transit = ledger.toss(lease.id(), owner, carrier, 1200);
        assertTrue(HomeControllerInventory.keepDropped(transit, carrier, 1, 1199));
        assertFalse(HomeControllerInventory.keepDropped(transit, UUID.randomUUID(), 1, 10));
        assertFalse(HomeControllerInventory.keepForPlayer(transit, receiver, 10));
        var pending = ledger.pickup(lease.id(), carrier, receiver, 1400);
        assertTrue(HomeControllerInventory.keepForPlayer(pending, receiver, 1200)); // Post-pickup inventoryTick must keep it.
        assertFalse(HomeControllerInventory.keepForPlayer(pending, owner, 1200));
        assertTrue(ledger.activate(pending.id(), receiver, CONSOLE, 1, 1));
        assertTrue(HomeControllerInventory.keepForPlayer(ledger.get(pending.id()), receiver, 9999));
        assertFalse(HomeControllerInventory.keepDropped(transit, carrier, 1, 1200));
        var expiredDrop = new PhysicalItem(transit.id(), true, 1);
        assertEquals(1, recycle(List.of(expiredDrop), transit.id()));
    }
    @Test void expiredPendingAndOldInvalidLoansAreLocallyConsumedWithoutTouchingNormalItems() {
        var ledger = new HomeControllerLedger(); var owner = UUID.randomUUID(); var receiver = UUID.randomUUID(); var carrier = UUID.randomUUID();
        var lease = ledger.issue(CONSOLE, 1, 1, owner); ledger.toss(lease.id(), owner, carrier, 100);
        var pending = ledger.pickup(lease.id(), carrier, receiver, 200);
        assertFalse(HomeControllerInventory.keepForPlayer(pending, receiver, 200));
        var oldShell = new PhysicalItem(null, true, 5); var unrelated = new PhysicalItem(null, false, 64);
        var expired = new PhysicalItem(pending.id(), true, 1);
        assertEquals(6, HomeControllerInventory.recycle(List.of(oldShell, unrelated, expired),
                item -> item.borrowed && !HomeControllerInventory.keepForPlayer(ledger.get(item.token), receiver, 200),
                item -> item.count, item -> item.count = 0));
        assertEquals(64, unrelated.count);
    }
    @Test void personalInventoryAndMenuAliasesDoNotLookLikeDuplicatedControllers() {
        var token = UUID.randomUUID(); var sameItem = new PhysicalItem(token, true, 1);
        assertEquals(HomeControllerInventory.Status.UNIQUE,
                HomeControllerInventory.locate(token, List.of(sameItem, sameItem), item -> item.token, item -> item.count).status());
        assertEquals(HomeControllerInventory.Status.AMBIGUOUS,
                HomeControllerInventory.locate(token, List.of(sameItem, sameItem.copy()), item -> item.token, item -> item.count).status());
    }
    @Test void liveP2AndDeathDropReturnWhileCopiedTokensDoNotRevokeOwnersLoan() {
        var ledger = new HomeControllerLedger(); var owner = UUID.randomUUID(); var p2 = ledger.issue(CONSOLE, 1, 1, owner);
        assertEquals(HomeControllerInventory.TossAction.RETURN_LOAN,
                HomeControllerInventory.tossAction(p2, owner, true, true, 0));
        assertEquals(HomeControllerInventory.TossAction.RETURN_LOAN,
                HomeControllerInventory.tossAction(p2, owner, false, true, 0));
        assertEquals(HomeControllerInventory.TossAction.RECYCLE_COPY,
                HomeControllerInventory.tossAction(p2, owner, true, false, 0));
        assertEquals(HomeControllerInventory.TossAction.RECYCLE_COPY,
                HomeControllerInventory.tossAction(p2, UUID.randomUUID(), true, true, 0));
        var carrier = UUID.randomUUID(); ledger.toss(p2.id(), owner, carrier, 100);
        var pending = ledger.pickup(p2.id(), carrier, owner, 200);
        assertEquals(HomeControllerInventory.TossAction.RETURN_LOAN,
                HomeControllerInventory.tossAction(pending, owner, true, true, 200));
    }
    @Test void unpoweredDropReturnsExactlyTheSelectedPortWithoutChangingTheOtherPort() {
        for (int port = 0; port < 2; port++) {
            var ledger = new HomeControllerLedger(); var owner = UUID.randomUUID();
            var returned = ledger.borrow(CONSOLE,port,owner);
            var untouched = ledger.borrow(CONSOLE,1-port,UUID.randomUUID());
            assertEquals(HomeControllerInventory.TossAction.RETURN_LOAN,
                    HomeControllerInventory.tossAction(returned,owner,true,true,0));
            assertSame(returned,ledger.revoke(returned.id()));
            assertNull(ledger.socket(CONSOLE,port));assertSame(untouched,ledger.socket(CONSOLE,1-port));
            assertNotNull(ledger.borrow(CONSOLE,port,owner));
        }
    }
}
