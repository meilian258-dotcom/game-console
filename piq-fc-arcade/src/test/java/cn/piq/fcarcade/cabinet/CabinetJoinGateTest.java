package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetJoinGateTest {
    final UUID host=UUID.randomUUID(), lease=UUID.randomUUID(), guest=UUID.randomUUID();
    final CabinetJoinGate gate=new CabinetJoinGate(host,lease);
    CabinetJoinGate.Offer enable(){var offer=gate.offer(0);assertNotNull(offer);assertTrue(gate.allow(host,lease,offer.token(),true,1));return offer;}

    @Test void defaultsPrivateAndOfferDoesNotGrantAnything(){
        assertFalse(gate.enabled());assertNull(gate.request(guest,1,0));
        assertNotNull(gate.offer(0));assertFalse(gate.enabled());assertNull(gate.request(guest,1,1));
    }
    @Test void repeatedReadyNeverCreatesAnotherOfferOrChangesItsToken(){
        var offer=gate.offer(10);assertNotNull(offer);assertNull(gate.offer(11));
        assertTrue(gate.canAnswer(offer.token(),11));assertTrue(gate.allow(host,lease,offer.token(),true,11));assertNull(gate.offer(12));
    }
    @Test void offerRequiresExactHostLeaseToken(){
        var offer=gate.offer(0);
        assertFalse(gate.allow(guest,lease,offer.token(),true,1));
        assertFalse(gate.allow(host,UUID.randomUUID(),offer.token(),true,1));
        assertFalse(gate.allow(host,lease,UUID.randomUUID(),true,1));
        assertTrue(gate.canAnswer(offer.token(),1));assertTrue(gate.allow(host,lease,offer.token(),true,1));
    }
    @Test void oldOfferCannotToggleAnAcceptedOrDeclinedChoice(){
        var offer=enable();assertFalse(gate.allow(host,lease,offer.token(),false,2));assertTrue(gate.enabled());
        var declined=new CabinetJoinGate(host,UUID.randomUUID());var other=declined.offer(0);
        assertFalse(declined.allow(host,lease,other.token(),true,0)); // Foreign lease has no authority.
    }
    @Test void explicitDeclineConsumesOfferAndKeepsPrivate(){
        var offer=gate.offer(0);assertTrue(gate.allow(host,lease,offer.token(),false,1));
        assertFalse(gate.allow(host,lease,offer.token(),true,2));assertFalse(gate.enabled());
        assertNull(gate.request(guest,1,2));assertNull(gate.offer(1000));
    }
    @Test void offerExpiresAtExactDeadlineWithoutRevival(){
        var offer=gate.offer(20);assertEquals(620,offer.expires());assertNull(gate.expireOffer(619));
        assertTrue(gate.canAnswer(offer.token(),619));assertFalse(gate.canAnswer(offer.token(),620));
        assertFalse(gate.allow(host,lease,offer.token(),true,620));assertEquals(offer.token(),gate.expireOffer(620));
        assertNull(gate.expireOffer(621));assertNull(gate.offer(621));assertFalse(gate.enabled());
    }
    @Test void onlyP2ThroughP4MayBeRequestedAndHostCannotApply(){
        enable();assertNull(gate.request(host,1,1));
        for(int port:new int[]{-1,0,4,99})assertNull(gate.request(guest,port,1));
        assertNull(gate.request(guest,1,-1));assertNotNull(gate.request(guest,1,1));
    }
    @Test void onePendingDoesNotReplaceEarlierApplicantOrSeat(){
        enable();var first=gate.request(guest,2,1);
        assertNull(gate.request(guest,3,2));assertNull(gate.request(UUID.randomUUID(),1,2));
        assertSame(first,gate.pending());assertEquals(2,gate.pending().port());
    }
    @Test void approveRequiresHostAndCurrentMembershipAndExactToken(){
        enable();var pending=gate.request(guest,1,1);
        assertNull(gate.decide(guest,lease,pending.token(),2));assertNull(gate.decide(host,UUID.randomUUID(),pending.token(),2));
        assertNull(gate.decide(host,lease,UUID.randomUUID(),2));assertSame(pending,gate.pending());
        assertSame(pending,gate.decide(host,lease,pending.token(),2));assertNull(gate.pending());
    }
    @Test void rejectionAndAcceptanceAreBothSingleUseTakes(){
        enable();var pending=gate.request(guest,1,1);assertSame(pending,gate.decide(host,lease,pending.token(),2));
        assertNull(gate.decide(host,lease,pending.token(),3));assertTrue(gate.enabled());
    }
    @Test void applicantCancelImmediatelyInvalidatesOldApproval(){
        enable();var pending=gate.request(guest,1,1);
        assertNull(gate.cancel(UUID.randomUUID()));assertSame(pending,gate.cancel(guest));
        assertNull(gate.decide(host,lease,pending.token(),2));assertNull(gate.cancel(guest));
    }
    @Test void cancellationThenReapplyUsesNewTokenAndOldAnswerCannotTakeIt(){
        enable();var old=gate.request(guest,1,1);gate.cancel(guest);
        assertNull(gate.request(guest,1,20));var next=gate.request(guest,1,21);assertNotNull(next);
        assertNotEquals(old.token(),next.token());assertNull(gate.decide(host,lease,old.token(),22));assertSame(next,gate.pending());
    }
    @Test void pendingDeadlineIsThirtySecondsAndExactBoundaryRejects(){
        enable();var pending=gate.request(guest,3,10);assertEquals(610,pending.expires());
        assertNull(gate.expire(609));assertNull(gate.decide(host,lease,pending.token(),610));
        assertSame(pending,gate.expire(610));assertNull(gate.expire(611));assertTrue(gate.enabled());
    }
    @Test void retiredRoomCannotLeakConsentToAnotherRoomWithSameHost(){
        enable();var old=gate.request(guest,1,1);gate.clear();
        var replacement=new CabinetJoinGate(host,UUID.randomUUID());
        assertFalse(replacement.enabled());assertNull(replacement.decide(host,lease,old.token(),2));
    }
    @Test void pendingGateDoesNotAllocateRoomInputOrViewerRoles(){
        var ledger=new CabinetRoomLedger<String>();var room=ledger.open(host,"primary","mame",4,0);
        ledger.ready(host,room.id,room.host().id,0);var consent=new CabinetJoinGate(host,room.host().id);
        var offer=consent.offer(0);consent.allow(host,room.host().id,offer.token(),true,1);
        var pending=consent.request(guest,2,1);assertNotNull(pending);
        assertEquals(1,ledger.allMembers().size());assertNull(ledger.player(guest));assertNull(room.members[2]);
        assertNull(ledger.input(guest,room.id,pending.token(),0,4095,1));
    }
    @Test void approvedExactPortKeepsOtherCabinetSeatsAndFreshMembershipIdentity(){
        var ledger=new CabinetRoomLedger<String>();var room=ledger.open(host,"primary","mame",4,0);
        ledger.ready(host,room.id,room.host().id,0);var consent=new CabinetJoinGate(host,room.host().id);
        var offer=consent.offer(0);consent.allow(host,room.host().id,offer.token(),true,1);
        var pending=consent.request(guest,2,1);var approved=consent.decide(host,room.host().id,pending.token(),2);
        var member=ledger.join(approved.applicant(),room,2,approved.port(),approved.port()+1);
        assertEquals(2,member.port);assertNull(room.members[1]);assertNull(room.members[3]);
        assertNotEquals(pending.token(),member.id);assertNull(consent.decide(host,room.host().id,pending.token(),3));
        ledger.remove(guest,member.id);assertSame(room,ledger.get(room.id));assertSame(room.host(),ledger.player(host));
    }
    @Test void approvalCannotMoveToAnotherSeatIfRequestedPortWasFilled(){
        var ledger=new CabinetRoomLedger<String>();var room=ledger.open(host,"primary","mame",4,0);
        ledger.ready(host,room.id,room.host().id,0);ledger.join(UUID.randomUUID(),room,0,2,3);
        assertNull(ledger.join(guest,room,1,2,3));assertNull(room.members[1]);assertNull(room.members[3]);
    }
    @Test void clearIsIdempotentAndRetiresPendingForLogoutOrMachineRemoval(){
        enable();var p=gate.request(guest,1,1);assertSame(p,gate.clear());assertNull(gate.clear());
        assertNull(gate.decide(host,lease,p.token(),2));
    }
}
