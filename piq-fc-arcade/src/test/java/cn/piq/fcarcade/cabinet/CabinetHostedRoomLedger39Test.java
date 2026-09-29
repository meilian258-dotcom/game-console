package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CabinetHostedRoomLedger39Test {
    private final CabinetRoomLedger<String> ledger=new CabinetRoomLedger<>();
    private final UUID owner=UUID.randomUUID();
    private CabinetRoomLedger.Room<String> hosted(int capacity){
        var room=ledger.open(owner,"hosted","nes",capacity,0);
        room.mode=CabinetSyncMode.SERVER_MEDIA;room.ready=true;return room;
    }
    private CabinetRoomLedger.Member join(CabinetRoomLedger.Room<String> room){
        var member=ledger.join(UUID.randomUUID(),room,1);assertNotNull(member);return member;
    }

    @Test void initialPlayerIdentityIsStableAndSeparateFromActiveManager(){
        var room=hosted(4);var original=room.host();var p2=join(room);
        assertEquals(original.id,room.streamHostId);assertEquals(owner,room.ownerId);
        assertEquals(List.of(original),ledger.remove(owner,original.id));
        assertSame(p2,room.host());assertEquals(1,p2.port);assertNull(room.members[0]);
        assertEquals(original.id,room.streamHostId);assertEquals(owner,room.ownerId);
        assertSame(room,ledger.get(room.id));assertTrue(room.ready);
    }

    @Test void p1CanBeReoccupiedWithoutMovingOrReplacingP2(){
        var room=hosted(2);var original=room.host();var p2=join(room);
        assertNotNull(ledger.input(p2.player,room.id,p2.id,7,5,1));
        ledger.remove(owner,original.id);
        var replacement=join(room);
        assertEquals(0,replacement.port);assertSame(replacement,room.host());
        assertNotEquals(original.id,replacement.id);assertEquals(original.id,room.streamHostId);
        assertSame(p2,room.members[1]);assertEquals(7,p2.sequence);assertEquals(5,p2.mask);
        assertNotNull(ledger.input(p2.player,room.id,p2.id,8,0,2));
    }

    @Test void explicitPrimaryRangeMayReuseP1ButNeverCrossesLinkedRanges(){
        var room=hosted(4);var original=room.host();
        var p3=ledger.join(UUID.randomUUID(),room,1,2,4);
        var p4=ledger.join(UUID.randomUUID(),room,1,2,4);
        assertEquals(2,p3.port);assertEquals(3,p4.port);
        ledger.remove(owner,original.id);assertSame(p3,room.host());
        var p1=ledger.join(UUID.randomUUID(),room,2,0,2);
        var p2=ledger.join(UUID.randomUUID(),room,2,0,2);
        assertEquals(0,p1.port);assertEquals(1,p2.port);
        assertNull(ledger.join(UUID.randomUUID(),room,2,0,2));
        assertSame(p3,room.members[2]);assertSame(p4,room.members[3]);
    }

    @Test void currentManagerDepartureRetainsRemainingPortsAndStableWorkerIdentity(){
        var room=hosted(4);var initial=room.host();var p2=join(room);var p3=join(room);var p4=join(room);
        ledger.remove(owner,initial.id);assertSame(p2,room.host());
        assertEquals(List.of(p2),ledger.remove(p2.player,p2.id));assertSame(p3,room.host());
        assertEquals(2,p3.port);assertEquals(3,p4.port);assertSame(p4,room.members[3]);
        assertEquals(initial.id,room.streamHostId);assertEquals(owner,room.ownerId);
        assertEquals(2,ledger.allMembers().size());assertSame(room,ledger.target("hosted"));
    }

    @Test void lastDepartureRemovesEveryIndexAndAllowsFreshRoom(){
        var room=hosted(2);var original=room.host();var p2=join(room);
        ledger.remove(owner,original.id);
        assertEquals(List.of(p2),ledger.remove(p2.player,p2.id));
        assertNull(room.host());assertNull(ledger.get(room.id));assertNull(ledger.target("hosted"));
        assertNull(ledger.member(original.id));assertNull(ledger.member(p2.id));
        assertNull(ledger.player(owner));assertNull(ledger.player(p2.player));
        assertTrue(ledger.all().isEmpty());assertTrue(ledger.allMembers().isEmpty());
        var fresh=ledger.open(owner,"hosted","nes",2,3);assertNotNull(fresh);
        assertNotEquals(room.id,fresh.id);assertNotEquals(room.streamHostId,fresh.streamHostId);
    }

    @Test void singleSeatHostedRoomStillClosesOnItsLastMember(){
        var room=hosted(1);var host=room.host();assertNull(ledger.join(UUID.randomUUID(),room,1));
        assertEquals(List.of(host),ledger.remove(owner,host.id));assertNull(ledger.get(room.id));
        assertNull(room.host());assertTrue(ledger.allMembers().isEmpty());
    }

    @Test void forceCloseRemovesHostedMembersEvenWhenOriginalP1IsAbsent(){
        var room=hosted(4);var original=room.host();var p2=join(room);var p3=join(room);
        ledger.remove(owner,original.id);
        assertEquals(List.of(p2,p3),ledger.removeRoom(room.id));
        assertNull(room.host());assertTrue(Arrays.stream(room.members).allMatch(Objects::isNull));
        assertTrue(ledger.all().isEmpty());assertTrue(ledger.allMembers().isEmpty());
        assertNull(ledger.player(p2.player));assertNull(ledger.player(p3.player));
        assertTrue(ledger.removeRoom(room.id).isEmpty());assertTrue(ledger.remove(p2.player,p2.id).isEmpty());
        assertNull(ledger.join(UUID.randomUUID(),room,3,0,4));
    }

    @Test void forceCloseOnlyAffectsTheExactRoomAndDoesNotDeleteReusedPlayerIndex(){
        var room=hosted(2);var original=room.host();var p2=join(room);ledger.remove(owner,original.id);
        var other=ledger.open(owner,"other","nes",2,2);assertNotNull(other);
        assertEquals(List.of(p2),ledger.removeRoom(room.id));
        assertSame(other,ledger.get(other.id));assertSame(other.host(),ledger.player(owner));
        assertTrue(ledger.removeRoom(UUID.randomUUID()).isEmpty());assertEquals(1,ledger.all().size());
    }

    @Test void staleOriginalMembershipCannotAffectAReplacementOrWorkerIdentity(){
        var room=hosted(2);var original=room.host();var p2=join(room);ledger.remove(owner,original.id);
        var replacement=ledger.join(owner,room,2);assertNotNull(replacement);assertEquals(0,replacement.port);
        assertTrue(ledger.remove(owner,original.id).isEmpty());assertNull(ledger.input(owner,room.id,original.id,99,1,2));
        assertFalse(ledger.heartbeat(owner,original.id,2));assertFalse(ledger.ready(owner,room.id,original.id,2));
        assertSame(replacement,ledger.player(owner));assertSame(p2,room.members[1]);assertEquals(original.id,room.streamHostId);
    }

    @Test void ordinaryModesStillRejectPortZeroAndCloseAllOnOriginalHostDeparture(){
        for(var mode:List.of(CabinetSyncMode.MEDIA,CabinetSyncMode.LOCAL_SYNC)){
            var local=new CabinetRoomLedger<String>();var room=local.open(owner,"ordinary","nes",4,0);
            room.mode=mode;room.ready=true;var host=room.host();var guest=local.join(UUID.randomUUID(),room,1);
            assertNull(local.join(UUID.randomUUID(),room,1,0,4));assertEquals(1,guest.port);
            assertSame(host,room.host());assertEquals(List.of(host,guest),local.remove(owner,host.id));
            assertTrue(local.all().isEmpty());assertTrue(local.allMembers().isEmpty());assertNull(local.player(guest.player));
        }
    }

    @Test void forceCloseWorksForOrdinaryRoomsToo(){
        for(var mode:List.of(CabinetSyncMode.MEDIA,CabinetSyncMode.LOCAL_SYNC)){
            var local=new CabinetRoomLedger<String>();var room=local.open(owner,"ordinary","nes",2,0);
            room.mode=mode;room.ready=true;var host=room.host();var guest=local.join(UUID.randomUUID(),room,1);
            assertEquals(List.of(host,guest),local.removeRoom(room.id));assertNull(room.host());
            assertTrue(local.all().isEmpty());assertTrue(local.allMembers().isEmpty());
        }
    }

    @Test void hostedJoinRetainsReadinessOwnershipAndRangeChecks(){
        var room=hosted(4);room.ready=false;assertNull(ledger.join(UUID.randomUUID(),room,1,0,4));
        room.ready=true;assertNull(ledger.join(owner,room,1,0,4));
        assertNull(ledger.join(UUID.randomUUID(),room,1,-1,4));assertNull(ledger.join(UUID.randomUUID(),room,1,0,5));
        assertNull(ledger.join(UUID.randomUUID(),room,1,2,2));assertNull(ledger.join(UUID.randomUUID(),room,1,3,2));
        var otherLedger=new CabinetRoomLedger<String>();assertNull(otherLedger.join(UUID.randomUUID(),room,1,0,4));
    }
}
