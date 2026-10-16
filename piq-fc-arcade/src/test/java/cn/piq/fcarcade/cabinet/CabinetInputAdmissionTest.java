package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetInputAdmissionTest {
    private long nanos;
    private final CabinetRoomLedger<String> ledger=new CabinetRoomLedger<>(()->nanos);
    private final UUID host=UUID.randomUUID(),guest=UUID.randomUUID();

    private CabinetRoomLedger.Room<String> room() {
        var room=ledger.open(host,"cabinet",CabinetCoinPolicy.BACKEND,4,0);
        assertNotNull(room);assertTrue(ledger.ready(host,room.id,room.host().id,0));return room;
    }

    @Test void originalRapidInputReproductionNowAdmitsEveryChangeAndKeepalive() {
        for(int changes:new int[]{40,50,60,120})for(int tps:new int[]{0,10,20}) {
            long[] clock={0};var local=new CabinetRoomLedger<String>(()->clock[0]);
            UUID owner=UUID.randomUUID();var r=local.open(owner,"cabinet","fbneo",2,0);
            assertTrue(local.ready(owner,r.id,r.id,0));long sequence=0;int accepted=0,mask=0,produced=0;
            for(int millis=1;millis<=10_000;millis++) {
                clock[0]=millis*1_000_000L;long tick=(long)millis*tps/1000;
                assertTrue(local.heartbeat(owner,r.id,tick));
                int due=millis*changes/1000;
                while(produced<due) {
                    mask=(produced++&1)==0?1:2;
                    var change=local.input(owner,r.id,r.id,sequence++,mask,tick,true);
                    assertNotNull(change,"changes="+changes+", TPS="+tps+", ms="+millis);
                    assertEquals(mask,change.mask());accepted++;
                }
                if(millis%250==0) {
                    assertNull(local.input(owner,r.id,r.id,sequence++,mask,tick,true));
                    assertEquals(sequence-1,r.host().sequence,"admitted refresh, not a rejection");
                }
                assertFalse(r.host().rateLimited);
            }
            assertEquals(changes*10,accepted);
        }
    }

    @Test void canonicalBurstAllowsSixtyFourOrderedChangesNotUnlimitedFlood() {
        var r=room();var member=ledger.join(guest,r,0);
        for(int n=0;n<64;n++) {
            var change=ledger.input(guest,r.id,member.id,n,(n&1)==0?1:2,0,true);
            assertNotNull(change);assertEquals(n+1,change.sequence());
        }
        assertNull(ledger.input(guest,r.id,member.id,64,1,0,true));
        assertTrue(member.rateLimited);assertEquals(63,member.sequence);
        assertEquals(2,member.mask,"denied change is not applied or synthesized as a keepalive");
        assertFalse(r.host().rateLimited);assertEquals(1,ledger.remove(guest,member.id).size());
        assertSame(r,ledger.get(r.id));
    }

    @Test void sameTickNetworkBatchNoLongerTripsAtSeventeenInCanonicalPath() {
        var r=room();
        for(int n=0;n<48;n++)assertNotNull(ledger.input(host,r.id,r.id,n,(n&1)==0?1:2,0,true));
        assertFalse(r.host().rateLimited);assertSame(r,ledger.get(r.id));
    }

    @Test void eachSeatHasIndependentCreditsAndNoPeerInputIsCleared() {
        var r=room();var p2=ledger.join(guest,r,0);
        for(int n=0;n<64;n++) {
            assertNotNull(ledger.input(host,r.id,r.id,n,(n&1)==0?1:2,0,true));
            assertNotNull(ledger.input(guest,r.id,p2.id,n,(n&1)==0?16:32,0,true));
        }
        assertNotNull(ledger.resetInput(guest,r.id,p2.id,64,0,true));
        assertFalse(p2.rateLimited);assertEquals(0,p2.mask);assertEquals(2,r.host().mask);
    }

    @Test void heldRefreshesDoNotSpendCanonicalChangeAllowance() {
        var r=room();long sequence=0;
        for(int n=0;n<64;n++) {
            int mask=(n&1)==0?1:2;
            assertNotNull(ledger.input(host,r.id,r.id,sequence++,mask,0,true));
            assertNull(ledger.input(host,r.id,r.id,sequence++,mask,0,true));
            assertFalse(r.host().rateLimited);
        }
        assertEquals(127,r.host().sequence);
        assertNull(ledger.input(host,r.id,r.id,128,2,0,true));
        assertTrue(r.host().rateLimited,"repeated same-mask transport is still bounded");
        assertEquals(127,r.host().sequence);
    }

    @Test void resetAfterAllEdgeCreditIsConsumedStillReleasesWithoutExit() {
        var r=room();
        for(int n=0;n<64;n++)assertNotNull(ledger.input(host,r.id,r.id,n,(n&1)==0?1:2,0,true));
        var released=ledger.resetInput(host,r.id,r.id,64,0,true);
        assertNotNull(released);assertTrue(released.reset());assertEquals(0,released.mask());
        assertFalse(r.host().rateLimited);assertFalse(r.host().inputSeen);
    }

    @Test void resetAtTransportExhaustionReturnsSafeReleaseBeforeFloodClose() {
        var r=room();var p2=ledger.join(guest,r,0);
        assertNotNull(ledger.input(host,r.id,r.id,0,32,0,true));
        assertNotNull(ledger.input(guest,r.id,p2.id,0,1,0,true));
        for(int n=1;n<128;n++)assertNull(ledger.input(guest,r.id,p2.id,n,1,0,true));
        assertFalse(p2.rateLimited);
        var released=ledger.resetInput(guest,r.id,p2.id,128,0,true);
        assertNotNull(released);assertTrue(released.reset());assertEquals(0,released.mask());
        assertTrue(p2.rateLimited);assertFalse(p2.inputSeen);assertEquals(0,p2.mask);
        assertEquals(32,r.host().mask);assertFalse(r.host().rateLimited);
        assertNull(ledger.resetInput(guest,r.id,p2.id,129,0,true),"cannot emit unbounded releases after denial");
    }

    @Test void repeatedResetsCannotBypassTotalTransportLimit() {
        var r=room();for(int n=0;n<128;n++)assertNotNull(ledger.resetInput(host,r.id,r.id,n,0,true));
        assertFalse(r.host().rateLimited);
        assertNotNull(ledger.resetInput(host,r.id,r.id,128,0,true));assertTrue(r.host().rateLimited);
        assertNull(ledger.resetInput(host,r.id,r.id,129,0,true));
    }

    @Test void staleOrForgedResetCannotReleaseAnySeat() {
        var r=room();var p2=ledger.join(guest,r,0);
        assertNotNull(ledger.input(guest,r.id,p2.id,9,1,0,true));
        assertNull(ledger.resetInput(host,r.id,p2.id,10,0,true));
        assertNull(ledger.resetInput(guest,UUID.randomUUID(),p2.id,10,0,true));
        assertNull(ledger.resetInput(guest,r.id,UUID.randomUUID(),10,0,true));
        assertNull(ledger.resetInput(guest,r.id,p2.id,9,0,true));
        assertEquals(1,p2.mask);assertTrue(p2.inputSeen);assertFalse(p2.rateLimited);
    }

    @Test void coinAndNoncontrollingFilteringPrecedesChangeAccounting() {
        var r=room();r.coinRequired=true;
        for(int n=0;n<100;n++)assertNull(ledger.input(host,r.id,r.id,n,(n&1)==0?4:0,0,true));
        assertNotNull(ledger.input(host,r.id,r.id,100,12,0,true));assertEquals(8,r.host().mask);
        assertFalse(r.host().rateLimited);r.host().controlling=false;
        assertEquals(0,ledger.input(host,r.id,r.id,101,4095,0,true).mask());
        for(int n=102;n<120;n++)assertNull(ledger.input(host,r.id,r.id,n,n,0,true));
        assertFalse(r.host().rateLimited);assertEquals(0,r.host().mask);
    }

    @Test void serverTickAdvanceDoesNotManufactureInputCredit() {
        var r=room();for(int n=0;n<64;n++)assertNotNull(ledger.input(host,r.id,r.id,n,(n&1)==0?1:2,n,true));
        assertTrue(ledger.heartbeat(host,r.id,64));
        assertNull(ledger.input(host,r.id,r.id,64,1,65,true));assertTrue(r.host().rateLimited);
    }

    @Test void invalidPayloadAndReplayCannotApplyOrConsumeAcceptedSequence() {
        var r=room();assertNotNull(ledger.input(host,r.id,r.id,10,1,0,true));
        for(int n=0;n<200;n++) {
            assertNull(ledger.input(host,r.id,r.id,10,2,0,true));
            assertNull(ledger.input(host,r.id,r.id,11,4096,0,true));
            assertNull(ledger.input(guest,r.id,r.id,11,2,0,true));
        }
        assertFalse(r.host().rateLimited);assertEquals(10,r.host().sequence);assertEquals(1,r.host().mask);
        assertNotNull(ledger.input(host,r.id,r.id,11,2,0,true));
    }

    @Test void monotonicBudgetDoesNotRefillOnBackwardsClockOrBeyondBurst() {
        var gate=new CabinetInputBudget(-2_000_000_000L);
        for(int n=0;n<64;n++)assertTrue(gate.input(true,-2_000_000_000L));
        assertFalse(gate.input(true,-3_000_000_000L));
        assertFalse(gate.input(true,-2_000_000_000L));
        for(int n=0;n<64;n++)assertTrue(gate.input(true,20_000_000_000L));
        assertFalse(gate.input(true,20_000_000_000L));
    }

    @Test void monotonicClockWrapUsesElapsedDifference() {
        long before=Long.MAX_VALUE-10_000_000L;var gate=new CabinetInputBudget(before);
        for(int n=0;n<64;n++)assertTrue(gate.input(true,before));
        assertTrue(gate.input(true,before+25_000_000L));
    }

    @Test void legacyPathsStillCountKeepalivesAndCannotBorrowNanosecondCredit() {
        var r=room();for(int n=0;n<16;n++) {
            nanos=n*1_000_000_000L;
            assertNull(ledger.input(host,r.id,r.id,n,0,0));assertFalse(r.host().rateLimited);
        }
        nanos=Long.MAX_VALUE;
        assertNull(ledger.input(host,r.id,r.id,16,0,0));assertTrue(r.host().rateLimited);
        assertEquals(15,r.host().sequence);
    }

    @Test void legacyResetStillUsesItsOriginalBudgetButReturnsSafeRelease() {
        var r=room();
        for(int n=0;n<16;n++)assertNotNull(ledger.input(host,r.id,r.id,n,(n&1)==0?1:2,0));
        var change=ledger.resetInput(host,r.id,r.id,16,0);
        assertTrue(change.reset());assertEquals(0,change.mask());assertTrue(r.host().rateLimited);
    }

    @Test void realLocalSyncTimelineRemainsBoundedWhenServerRunsAtTenTps() {
        var r=room();r.mode=CabinetSyncMode.LOCAL_SYNC;
        var timeline=new CabinetSyncTimeline(40_000);long sequence=0;
        for(int n=0;n<16;n++) {
            var change=ledger.input(host,r.id,r.id,sequence++,(n&1)==0?1:2,0);
            assertTrue(timeline.input(0,change.mask()));
        }
        for(int tick=1;tick<=200;tick++) {
            nanos=tick*100_000_000L;assertTrue(ledger.heartbeat(host,r.id,tick));
            for(int n=0;n<2;n++) {
                var change=ledger.input(host,r.id,r.id,sequence++,(n&1)==0?1:2,tick);
                assertNotNull(change);assertTrue(timeline.input(0,change.mask()),"32-entry queue never overflows");
            }
            var frames=timeline.tick();assertEquals(2,frames.size());
            assertEquals(1,frames.get(0).p1());assertEquals(2,frames.get(1).p1());
            assertFalse(r.host().rateLimited);
        }
        assertEquals(400,timeline.frame());
    }
}
