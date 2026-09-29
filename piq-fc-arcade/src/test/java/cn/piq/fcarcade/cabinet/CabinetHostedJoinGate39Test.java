package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CabinetHostedJoinGate39Test {
    private final UUID manager=UUID.randomUUID(),lease=UUID.randomUUID(),guest=UUID.randomUUID();
    private CabinetJoinGate enabled(boolean first){
        var gate=new CabinetJoinGate(manager,lease,first);var offer=gate.offer(0);
        assertTrue(gate.allow(manager,lease,offer.token(),true,1));return gate;
    }

    @Test void hostedCanRequestPortZeroOnlyAfterExplicitConsent(){
        var gate=new CabinetJoinGate(manager,lease,true);
        assertNull(gate.request(guest,0,0));var offer=gate.offer(0);assertNull(gate.request(guest,0,1));
        assertTrue(gate.allow(manager,lease,offer.token(),true,1));
        var pending=gate.request(guest,0,2);assertNotNull(pending);assertEquals(0,pending.port());
        assertEquals(pending,gate.decide(manager,lease,pending.token(),3));
        assertNull(gate.decide(manager,lease,pending.token(),4));
    }

    @Test void ordinaryTwoArgumentAndExplicitFalseStillRejectP1(){
        var legacy=new CabinetJoinGate(manager,lease);var offer=legacy.offer(0);
        assertTrue(legacy.allow(manager,lease,offer.token(),true,1));assertNull(legacy.request(guest,0,2));
        var explicit=enabled(false);assertNull(explicit.request(guest,0,2));assertNotNull(explicit.request(guest,1,2));
    }

    @Test void hostedP1StillRequiresExactManagerLeaseAndCurrentToken(){
        var gate=enabled(true);var pending=gate.request(guest,0,2);
        assertNull(gate.decide(guest,lease,pending.token(),3));
        assertNull(gate.decide(manager,UUID.randomUUID(),pending.token(),3));
        assertNull(gate.decide(manager,lease,UUID.randomUUID(),3));
        assertSame(pending,gate.pending());assertSame(pending,gate.decide(manager,lease,pending.token(),3));
    }

    @Test void hostedFirstPortDoesNotPermitSelfNegativeOrFifthSeat(){
        var gate=enabled(true);assertNull(gate.request(manager,0,2));
        for(int port:new int[]{-1,4,99})assertNull(gate.request(guest,port,2));
        assertNotNull(gate.request(guest,0,2));
    }

    @Test void hostedP1CancelAndExpiryRetireApprovalWithoutChangingOtherRules(){
        var gate=enabled(true);var first=gate.request(guest,0,2);
        assertSame(first,gate.cancel(guest));assertNull(gate.decide(manager,lease,first.token(),3));
        assertNull(gate.request(guest,0,21));var next=gate.request(guest,0,22);assertNotNull(next);
        assertNull(gate.decide(manager,lease,next.token(),622));assertSame(next,gate.expire(622));
    }

    @Test void approvedHostedP1ReusesOnlyVacantZeroAndKeepsP2Fixed(){
        var ledger=new CabinetRoomLedger<String>();var room=ledger.open(manager,"hosted","sfc",2,0);
        room.mode=CabinetSyncMode.SERVER_MEDIA;room.ready=true;var original=room.host();
        var p2=ledger.join(UUID.randomUUID(),room,1);ledger.remove(manager,original.id);
        var gate=new CabinetJoinGate(p2.player,p2.id,true);var offer=gate.offer(2);
        assertTrue(gate.allow(p2.player,p2.id,offer.token(),true,3));var pending=gate.request(guest,0,4);
        var accepted=gate.decide(p2.player,p2.id,pending.token(),5);
        var p1=ledger.join(accepted.applicant(),room,5,accepted.port(),accepted.port()+1);
        assertNotNull(p1);assertEquals(0,p1.port);assertSame(p2,room.members[1]);
        assertEquals(original.id,room.streamHostId);assertEquals(manager,room.ownerId);
    }
}
