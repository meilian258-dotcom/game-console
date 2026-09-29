package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetIdleShutdownTest {
    private static final long WAIT = 60_000_000_000L;

    @Test void customDelayDisabledAndReenabled(){
        var timer=new CabinetIdleShutdown<String>();
        timer.update("r",false,true,7,0);
        assertTrue(timer.pollDue(6_999_999_999L).isEmpty());
        assertEquals(List.of("r"),timer.pollDue(7_000_000_000L));
        timer.update("r",false,true,1,0);timer.update("r",false,false,1,1);
        assertTrue(timer.pollDue(WAIT*100).isEmpty());
        timer.update("r",false,true,3600,0);
        assertTrue(timer.pollDue(3_600_000_000_000L-1).isEmpty());
        assertEquals(List.of("r"),timer.pollDue(3_600_000_000_000L));
    }
    @Test void emptyTimerAndActiveSeatNeverExpire() {
        var timer = new CabinetIdleShutdown<String>();
        assertTrue(timer.pollDue(Long.MAX_VALUE).isEmpty());
        timer.update("room", true, 0);
        assertTrue(timer.pollDue(WAIT * 100).isEmpty());
    }

    @Test void closesExactlyAfterSixtySecondsAndOnlyOnce() {
        var timer = new CabinetIdleShutdown<String>();
        timer.update("room", false, 123);
        assertTrue(timer.pollDue(123 + WAIT - 1).isEmpty());
        assertEquals(List.of("room"), timer.pollDue(123 + WAIT));
        assertTrue(timer.pollDue(123 + WAIT + 1).isEmpty());
    }

    @Test void repeatedIdleNotificationDoesNotExtendDeadline() {
        var timer = new CabinetIdleShutdown<String>();
        timer.update("room", false, 0);
        timer.update("room", false, WAIT - 1);
        assertEquals(List.of("room"), timer.pollDue(WAIT));
    }

    @Test void resumeCancelsAndLaterExitGetsFreshSixtySeconds() {
        var timer = new CabinetIdleShutdown<String>();
        timer.update("room", false, 0);
        timer.update("room", true, WAIT - 1);
        assertTrue(timer.pollDue(WAIT).isEmpty());
        timer.update("room", false, WAIT + 10);
        assertTrue(timer.pollDue(2 * WAIT + 9).isEmpty());
        assertEquals(List.of("room"), timer.pollDue(2 * WAIT + 10));
    }

    @Test void cancellationDoesNotLeaveStaleDeadlineForReplacementRoom() {
        var timer = new CabinetIdleShutdown<String>();
        timer.update("old", false, 0);
        timer.cancel("old");
        timer.cancel("old");
        timer.update("new", false, 10);
        assertTrue(timer.pollDue(WAIT).isEmpty());
        assertEquals(List.of("new"), timer.pollDue(WAIT + 10));
    }

    @Test void multipleRoomsKeepIndependentDeadlinesAndEarliestIsRecomputed() {
        var timer = new CabinetIdleShutdown<String>();
        timer.update("a", false, 0);timer.update("b", false, 5);timer.update("c", false, 5);timer.update("d", false, 10);
        timer.cancel("a");
        assertTrue(timer.pollDue(WAIT).isEmpty());
        assertEquals(Set.of("b", "c"), Set.copyOf(timer.pollDue(WAIT + 5)));
        assertEquals(List.of("d"), timer.pollDue(WAIT + 10));
    }

    @Test void monotonicClockMayBeNegativeOrWrapAndDoesNotDependOnTickRate() {
        for (long start : new long[]{-2 * WAIT, -1, 0, Long.MAX_VALUE - WAIT / 2}) {
            var timer = new CabinetIdleShutdown<String>();
            timer.update("room", false, start);
            assertTrue(timer.pollDue(start + WAIT - 1).isEmpty());
            assertEquals(List.of("room"), timer.pollDue(start + WAIT + 999));
        }
    }

    @Test void linkedGuestStillControllingPreventsTimerEvenAfterHostExits() {
        for (var mode : CabinetSyncMode.values()) {
            var ledger = new CabinetRoomLedger<String>();
            var room = ledger.open(UUID.randomUUID(), "linked", CabinetCoinPolicy.BACKEND, 4, 0);
            room.mode = mode;room.ready = true;
            var guest = ledger.join(UUID.randomUUID(), room, 0, 2, 3);
            var timer = new CabinetIdleShutdown<UUID>();
            room.host().controlling = false;
            assertTrue(room.hasController());
            timer.update(room.id, room.hasController(), 0);
            assertTrue(timer.pollDue(WAIT).isEmpty());
            ledger.remove(guest.player, guest.id);
            assertFalse(room.hasController());
            timer.update(room.id, room.hasController(), WAIT);
            assertTrue(timer.pollDue(2 * WAIT - 1).isEmpty());
            assertEquals(List.of(room.id), timer.pollDue(2 * WAIT));
        }
    }

    @Test void occupiedSeatWithoutKeyPressesDoesNotMeanIdleAndRejoinCancels() {
        var ledger = new CabinetRoomLedger<String>();
        var room = ledger.open(UUID.randomUUID(), "cabinet", CabinetCoinPolicy.BACKEND, 4, 0);
        room.ready = true;
        assertEquals(0, room.host().mask);
        assertFalse(room.host().inputSeen);
        assertTrue(room.hasController());
        var timer = new CabinetIdleShutdown<UUID>();
        room.host().controlling = false;
        timer.update(room.id, room.hasController(), 0);
        assertNotNull(ledger.join(UUID.randomUUID(), room, 1));
        timer.update(room.id, room.hasController(), WAIT - 1);
        assertTrue(timer.pollDue(WAIT).isEmpty());
    }

    @Test void productionWiringUsesSeatEventsNotNearbyPlayerOrInputPolling() throws Exception {
        String src = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetRooms.java"));
        assertFalse(src.contains("noNearbyPlayers"));
        assertFalse(src.contains("CabinetPresencePolicy"));
        String update = src.substring(src.indexOf("private static void updateIdleShutdown"), src.indexOf("static void tick(MinecraftServer server)"));
        assertTrue(update.contains("!room.ready||!CabinetCoinPolicy.supported(room.backend)"));
        assertTrue(update.contains("room.hasController(),room.autoPowerOff,room.idleShutdownSeconds,System.nanoTime()"));
        assertFalse(update.contains("getPlayers()"));assertFalse(update.contains("distanceTo"));
        assertTrue(src.contains("own.controlling=!own.controlling;forward(server,state,state.ledger.reset(own));"));
        assertTrue(src.contains("if(!own.controlling&&closeAfterManualExit(server,state,room,true))return;\n                    updateIdleShutdown(state,room);"));
        assertTrue(src.contains("member.connection=request.connection();\n        updateIdleShutdown(state,room);"));
        assertTrue(src.contains("member.controlling=false;forward(server,state,state.ledger.reset(member));\n                updateIdleShutdown(state,room);"));
        assertTrue(src.contains("else updateIdleShutdown(state,room);"));
        assertTrue(src.contains("updateModerator(server,state,room);updateIdleShutdown(state,room);return;"));
        assertTrue(src.contains("state.idleShutdown.cancel(room.id);visualPower"));
        assertTrue(src.contains("private static void cleanupRoom(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room){\n        state.idleShutdown.cancel(room.id);"));
        assertTrue(src.indexOf("idleShutdown.pollDue(System.nanoTime())") < src.indexOf("CabinetSynchronizer.tick(server)"));
        assertFalse(src.contains("own.controlling&&binding.cabinet().autoPowerOffOnExit()"));
        assertTrue(src.contains("room.autoPowerOff&&CabinetCoinPolicy.supported(room.backend)"));
    }
}
