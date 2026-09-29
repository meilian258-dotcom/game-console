package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerArcadeSessionsTest {
    @Test
    void reconnectTransfersBackFromOtherCabinetBeforeRestoringOriginalRole() {
        UUID player = UUID.randomUUID();
        UUID partner = UUID.randomUUID();
        var original = new cn.piq.fcarcade.session.SessionRoster();
        var other = new cn.piq.fcarcade.session.SessionRoster();
        Map<UUID, String> memberships = new HashMap<>();
        original.join(player);
        original.join(partner);
        memberships.put(player, "original");
        var role = original.roleOf(player);
        original.remove(player);
        OwnedMemberships.removeIfOwnedBy(memberships, player, "original");
        other.join(player);
        memberships.put(player, "other");

        OwnedMemberships.leaveOtherBeforeTransfer(memberships, player, "original", () -> {
            assertTrue(other.remove(player));
            assertTrue(OwnedMemberships.removeIfOwnedBy(memberships, player, "other"));
        });
        assertFalse(other.contains(player));
        original.rejoin(player, role);
        memberships.put(player, "original");
        assertEquals(cn.piq.fcarcade.session.ArcadeRole.PLAYER_ONE, original.roleOf(player));
        assertFalse(OwnedMemberships.removeIfOwnedBy(memberships, player, "other"));
        assertEquals("original", memberships.get(player));
        OwnedMemberships.leaveOtherBeforeTransfer(memberships, player, "original", () -> {
            throw new AssertionError("Must not leave the destination cabinet");
        });
    }

    @Test
    void incompleteDepartureCannotSilentlyReplaceMembership() {
        UUID player = UUID.randomUUID();
        Map<UUID, String> memberships = new HashMap<>();
        memberships.put(player, "other");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> OwnedMemberships.leaveOtherBeforeTransfer(memberships, player, "original", () -> {}));
        assertEquals("other", memberships.get(player));
    }
    @Test
    void idleDisplayReconciliationRunsImmediatelyThenEveryMinute() {
        assertTrue(IdleDisplaySchedule.shouldReconcile(
                1,
                false));
        assertFalse(IdleDisplaySchedule.shouldReconcile(
                1_199,
                true));
        assertTrue(IdleDisplaySchedule.shouldReconcile(
                1_200,
                true));
        assertEquals(1_200,
                IdleDisplaySchedule.RECONCILE_INTERVAL_TICKS);
    }

    @Test
    void idleLeaderboardAdvancesOnePageEveryThreeSeconds() {
        assertFalse(IdleDisplaySchedule.shouldRotate(60, false));
        assertFalse(IdleDisplaySchedule.shouldRotate(59, true));
        assertTrue(IdleDisplaySchedule.shouldRotate(60, true));
        assertEquals(60, IdleDisplaySchedule.DEFAULT_ROTATE_INTERVAL_TICKS);

        assertEquals(0, IdleDisplaySchedule.windowStart(0, 10, 3));
        assertEquals(3, IdleDisplaySchedule.windowStart(60, 10, 3));
        assertEquals(6, IdleDisplaySchedule.windowStart(120, 10, 3));
        assertEquals(9, IdleDisplaySchedule.windowStart(180, 10, 3));
        assertEquals(0, IdleDisplaySchedule.windowStart(240, 10, 3));
        assertEquals(0, IdleDisplaySchedule.windowStart(240, 3, 3));
    }

    @Test
    void idleLeaderboardSupportsAConfiguredPageInterval() {
        assertFalse(IdleDisplaySchedule.shouldRotate(60, true, 100));
        assertTrue(IdleDisplaySchedule.shouldRotate(100, true, 100));
        assertEquals(0, IdleDisplaySchedule.windowStart(99, 10, 3, 100));
        assertEquals(3, IdleDisplaySchedule.windowStart(100, 10, 3, 100));
    }

    @Test
    void closingViewedCabinetKeepsMembershipForControlledCabinet() {
        UUID playerId = UUID.randomUUID();
        Map<UUID, String> memberships = new HashMap<>();
        memberships.put(playerId, "controlled-cabinet");

        boolean removed = OwnedMemberships.removeIfOwnedBy(
                memberships,
                playerId,
                "viewed-cabinet");

        assertFalse(removed);
        assertEquals("controlled-cabinet", memberships.get(playerId));
    }

    @Test
    void closingControlledCabinetRemovesItsMembership() {
        UUID playerId = UUID.randomUUID();
        Map<UUID, String> memberships = new HashMap<>();
        memberships.put(playerId, "controlled-cabinet");

        boolean removed = OwnedMemberships.removeIfOwnedBy(
                memberships,
                playerId,
                "controlled-cabinet");

        assertTrue(removed);
        assertFalse(memberships.containsKey(playerId));
    }
}
