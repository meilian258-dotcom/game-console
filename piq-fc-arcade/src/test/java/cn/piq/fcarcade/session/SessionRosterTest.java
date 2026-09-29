package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRosterTest {
    @Test
    void assignsTwoControllersThenSpectators() {
        SessionRoster roster = new SessionRoster();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(ArcadeRole.PLAYER_ONE, roster.join(first));
        assertEquals(ArcadeRole.PLAYER_TWO, roster.join(second));
        assertEquals(ArcadeRole.SPECTATOR, roster.join(third));
        assertEquals(3, roster.size());
    }

    @Test
    void promotesRemainingMembersWhenPlayerLeaves() {
        SessionRoster roster = new SessionRoster();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        roster.join(first);
        roster.join(second);
        roster.join(third);

        assertTrue(roster.remove(first));
        assertEquals(ArcadeRole.PLAYER_ONE, roster.roleOf(second));
        assertEquals(ArcadeRole.PLAYER_TWO, roster.roleOf(third));
        assertFalse(roster.contains(first));
    }

    @Test
    void duplicateJoinDoesNotChangeOrderOrCount() {
        SessionRoster roster = new SessionRoster();
        UUID first = UUID.randomUUID();

        assertEquals(ArcadeRole.PLAYER_ONE, roster.join(first));
        assertEquals(ArcadeRole.PLAYER_ONE, roster.join(first));
        assertEquals(1, roster.size());
    }

    @Test
    void restoresDisconnectedPlayerOneWithoutSwappingControllers() {
        SessionRoster roster = new SessionRoster();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        roster.join(first);
        roster.join(second);
        roster.remove(first);

        assertEquals(
                ArcadeRole.PLAYER_ONE,
                roster.rejoin(first, ArcadeRole.PLAYER_ONE));
        assertEquals(ArcadeRole.PLAYER_TWO, roster.roleOf(second));
    }
}
