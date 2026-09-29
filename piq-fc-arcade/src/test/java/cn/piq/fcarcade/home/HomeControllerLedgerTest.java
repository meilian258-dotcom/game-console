package cn.piq.fcarcade.home;

import cn.piq.fcarcade.session.LockstepState;
import cn.piq.fcarcade.session.ControllerDepartureInputs;
import cn.piq.fcarcade.session.SessionRoster;
import cn.piq.fcarcade.session.ArcadeRole;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerLedgerTest {
    private static final HomeControllerLedger.Console CONSOLE = new HomeControllerLedger.Console(UUID.randomUUID(), "minecraft:overworld", 1, 64, 2);
    private static HomeControllerLedger.Console otherConsole() {
        return new HomeControllerLedger.Console(UUID.randomUUID(), "minecraft:overworld", 1, 64, 2);
    }
    @Test void eachPlayerCanOwnOnlyOnePortAcrossAllMachines() {
        var ledger = new HomeControllerLedger(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID();
        assertNotNull(ledger.issue(CONSOLE, 1, 0, p1));
        assertNull(ledger.issue(CONSOLE, 1, 1, p1));
        assertNull(ledger.issue(otherConsole(), 2, 0, p1));
        assertNull(ledger.issue(CONSOLE, 1, 0, p2));
        assertNotNull(ledger.issue(CONSOLE, 1, 1, p2));
    }
    @Test void copiedOrCrossMachineTokensDoNotAuthorizeOtherPeopleOrGenerations() {
        var ledger = new HomeControllerLedger(); var p1 = UUID.randomUUID(); var lease = ledger.issue(CONSOLE, 1, 0, p1);
        assertTrue(ledger.authorized(lease.id(), p1, CONSOLE, 1, 0));
        assertFalse(ledger.authorized(lease.id(), UUID.randomUUID(), CONSOLE, 1, 0));
        assertFalse(ledger.authorized(lease.id(), p1, otherConsole(), 1, 0));
        assertFalse(ledger.authorized(lease.id(), p1, CONSOLE, 2, 0));
        assertFalse(ledger.authorized(lease.id(), p1, CONSOLE, 1, 1));
        assertFalse(new HomeControllerLedger().authorized(lease.id(), p1, CONSOLE, 1, 0));
    }
    @Test void p2TransferClearsOldInputAndRequiresNewReceiptThenApproval() {
        var ledger = new HomeControllerLedger(); var roster = new SessionRoster(); var input = new LockstepState();
        var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID(); var nextPlayer = UUID.randomUUID(); var entity = UUID.randomUUID();
        roster.join(p1); roster.join(p2); input.restart();
        var first = ledger.issue(CONSOLE, 9, 0, p1); var second = ledger.issue(CONSOLE, 9, 1, p2);
        assertTrue(input.acceptInput(p1, input.epoch(), 0, 0, 90));
        assertTrue(input.acceptInput(p2, input.epoch(), 1, 0, 255));
        assertNotNull(ledger.toss(second.id(), p2, entity, 1200));
        ControllerDepartureInputs.clear(input, true, roster.roleOf(p2).controllerIndex());
        input.forgetPlayer(p2); roster.remove(p2);
        var afterDeparture = input.advanceFrame();
        assertEquals(90, afterDeparture.playerOneMask());
        assertEquals(0, afterDeparture.playerTwoMask());
        assertEquals(ArcadeRole.PLAYER_ONE, roster.roleOf(p1));
        assertFalse(ledger.authorized(second.id(), p2, CONSOLE, 9, 1));
        assertNull(ledger.pickup(second.id(), UUID.randomUUID(), nextPlayer, 1200));
        var pending = ledger.pickup(second.id(), entity, nextPlayer, 1200);
        assertNotNull(pending); assertNotEquals(second.id(), pending.id());
        assertNull(ledger.get(second.id())); assertNull(ledger.pickup(second.id(), entity, nextPlayer, 1200));
        assertFalse(ledger.authorized(pending.id(), nextPlayer, CONSOLE, 9, 1));
        assertFalse(ledger.activate(pending.id(), p2, CONSOLE, 9, 1));
        assertFalse(ledger.activate(pending.id(), nextPlayer, CONSOLE, 10, 1));
        assertTrue(ledger.activate(pending.id(), nextPlayer, CONSOLE, 9, 1));
        assertEquals(ArcadeRole.PLAYER_TWO, roster.join(nextPlayer));
        assertTrue(ledger.authorized(pending.id(), nextPlayer, CONSOLE, 9, 1));
        assertTrue(ledger.authorized(first.id(), p1, CONSOLE, 9, 0));
    }
    @Test void onePlayerCannotPickUpASecondAuthorization() {
        var ledger = new HomeControllerLedger(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID(); var entity = UUID.randomUUID();
        ledger.issue(CONSOLE, 4, 0, p1); var second = ledger.issue(CONSOLE, 4, 1, p2);
        ledger.toss(second.id(), p2, entity, 100);
        assertNull(ledger.pickup(second.id(), entity, p1, 100));
        assertEquals(HomeControllerLedger.Phase.IN_TRANSIT, ledger.get(second.id()).phase());
        assertNull(ledger.issue(CONSOLE, 4, 1, UUID.randomUUID()));
    }
    @Test void p1CannotTransferAndSessionCloseRevokesBothIncludingPendingP2() {
        var ledger = new HomeControllerLedger(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID(); var entity = UUID.randomUUID();
        var first = ledger.issue(CONSOLE, 1, 0, p1); var second = ledger.issue(CONSOLE, 1, 1, p2);
        assertNull(ledger.toss(first.id(), p1, entity, 100));
        ledger.toss(second.id(), p2, entity, 100);
        var pending = ledger.pickup(second.id(), entity, UUID.randomUUID(), 100);
        assertEquals(2, ledger.revokeSession(1).size()); assertTrue(ledger.snapshots().isEmpty());
        assertFalse(ledger.activate(pending.id(), pending.player(), CONSOLE, 1, 1));
        assertNotNull(ledger.issue(CONSOLE, 2, 0, p2));
        assertFalse(ledger.authorized(first.id(), p1, CONSOLE, 2, 0));
    }
    @Test void expiredOrRejectedP2RecyclesPortWithoutAffectingP1() {
        var ledger = new HomeControllerLedger(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID();
        var first = ledger.issue(CONSOLE, 5, 0, p1); var second = ledger.issue(CONSOLE, 5, 1, p2);
        assertNotNull(ledger.revoke(second.id())); assertNull(ledger.revoke(second.id()));
        assertTrue(ledger.authorized(first.id(), p1, CONSOLE, 5, 0));
        assertNotNull(ledger.issue(CONSOLE, 5, 1, UUID.randomUUID()));
    }
    @Test void invalidPortsAndNonSessionClaimsCannotReserveAnything() {
        var ledger = new HomeControllerLedger(); var player = UUID.randomUUID();
        assertNull(ledger.issue(CONSOLE, 0, 0, player)); assertNull(ledger.issue(CONSOLE, 1, -1, player));
        assertNull(ledger.issue(CONSOLE, 1, 2, player)); assertNull(ledger.issue(null, 1, 0, player));
        assertTrue(ledger.snapshots().isEmpty());
    }
    @Test void stowingOneControllerClearsOnlyItsButtonsWithoutResettingSequencesOrFrames() {
        var input = new LockstepState(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID(); input.restart();
        assertTrue(input.acceptInput(p1, input.epoch(), 0, 10, 15)); assertTrue(input.acceptInput(p2, input.epoch(), 1, 20, 240));
        input.clearController(1); var frame = input.advanceFrame();
        assertEquals(15, frame.playerOneMask()); assertEquals(0, frame.playerTwoMask()); assertEquals(1, frame.targetFrame());
        assertFalse(input.acceptInput(p2, input.epoch(), 1, 19, 255));
        assertTrue(input.acceptInput(p2, input.epoch(), 1, 21, 64)); assertEquals(64, input.advanceFrame().playerTwoMask());
    }
    @Test void traditionalDepartureKeepsItsExistingBothPortResetPolicy() {
        var input = new LockstepState(); input.restart();
        assertTrue(input.acceptInput(UUID.randomUUID(), input.epoch(), 0, 0, 90));
        assertTrue(input.acceptInput(UUID.randomUUID(), input.epoch(), 1, 0, 255));
        ControllerDepartureInputs.clear(input, false, 1);
        var frame = input.advanceFrame();
        assertEquals(0, frame.playerOneMask()); assertEquals(0, frame.playerTwoMask());
    }
}
