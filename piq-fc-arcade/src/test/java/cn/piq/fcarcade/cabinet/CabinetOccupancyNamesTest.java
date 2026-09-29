package cn.piq.fcarcade.cabinet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetOccupancyNamesTest {
    @Test void everyTransportReportsOnlyCurrentSeatsInPortOrder() {
        for (var mode : CabinetSyncMode.values()) {
            var ledger = new CabinetRoomLedger<String>();
            var host = UUID.randomUUID();
            var room = ledger.open(host, "main", "test", 4, 0);
            room.mode = mode;
            var names = new HashMap<UUID,String>();
            names.put(host, "Alex");
            assertEquals("Alex", ledger.occupantNames(room, m -> names.get(m.player)));
            room.ready = true;
            var third = ledger.join(UUID.randomUUID(), room, 0, 2, 4);
            var second = ledger.join(UUID.randomUUID(), room, 0, 1, 2);
            names.put(third.player, "Steve"); names.put(second.player, "玩家二");
            // A nearby viewer or pending applicant has a name but no granted seat.
            names.put(UUID.randomUUID(), "Spectator");
            assertEquals("Alex、玩家二、Steve", ledger.occupantNames(room, m -> names.get(m.player)));
            ledger.remove(second.player, second.id);
            assertEquals("Alex、Steve", ledger.occupantNames(room, m -> names.get(m.player)));
        }
    }

    @Test void vanishedOrReplacedConnectionsCanBeExcludedWithoutRenewingTheLease() {
        var ledger = new CabinetRoomLedger<String>();
        var room = ledger.open(UUID.randomUUID(), "main", "test", 2, 0);
        room.ready = true;
        var guest = ledger.join(UUID.randomUUID(), room, 0);
        Object original = new Object(), replacement = new Object();
        room.host().connection = original; guest.connection = original;
        var connections = new HashMap<UUID,Object>();
        connections.put(room.host().player, original); connections.put(guest.player, replacement);
        assertEquals("P1", ledger.occupantNames(room, m -> connections.get(m.player) == m.connection ? "P" + (m.port + 1) : null));
        assertEquals(80, guest.expires);
        connections.clear();
        assertEquals("", ledger.occupantNames(room, m -> connections.get(m.player) == m.connection ? "player" : null));
        assertEquals(80, room.host().expires);
    }

    @Test void playerHostedClosureRemovesNamesEvenFromAnOldRoomReference() {
        for (var mode : new CabinetSyncMode[]{CabinetSyncMode.MEDIA, CabinetSyncMode.LOCAL_SYNC}) {
            var ledger = new CabinetRoomLedger<String>();
            var room = ledger.open(UUID.randomUUID(), "main", "test", 2, 0);
            room.mode = mode; room.ready = true;
            ledger.join(UUID.randomUUID(), room, 0);
            ledger.remove(room.host().player, room.host().id);
            assertEquals("", ledger.occupantNames(room, m -> "stale"));
            var next = ledger.open(UUID.randomUUID(), "main", "test", 2, 1);
            assertEquals("", ledger.occupantNames(room, m -> "old"));
            assertEquals("new", ledger.occupantNames(next, m -> "new"));
        }
    }

    @Test void serverHostedOwnerDepartureKeepsOnlyTheRemainingActualController() {
        var ledger = new CabinetRoomLedger<String>();
        var room = ledger.open(UUID.randomUUID(), "main", "test", 2, 0);
        room.mode = CabinetSyncMode.SERVER_MEDIA; room.ready = true;
        var original = room.host(); var guest = ledger.join(UUID.randomUUID(), room, 0);
        Map<UUID,String> names = Map.of(original.player, "Original", guest.player, "Guest");
        ledger.remove(original.player, original.id);
        assertEquals(original.player, room.ownerId); // Save ownership is unrelated to occupancy.
        assertEquals("Guest", ledger.occupantNames(room, m -> names.get(m.player)));
        ledger.remove(guest.player, guest.id);
        assertEquals("", ledger.occupantNames(room, m -> names.get(m.player)));
    }

    @Test void noGenericPlayerPlaceholderOrEmptyNameIsInvented() {
        var ledger = new CabinetRoomLedger<String>();
        var room = ledger.open(UUID.randomUUID(), "main", "test", 1, 0);
        assertEquals("", ledger.occupantNames(room, m -> null));
        assertEquals("", ledger.occupantNames(room, m -> "  "));
        assertEquals("", ledger.occupantNames(null, m -> "phantom"));
    }
}
