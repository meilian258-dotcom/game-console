package cn.piq.fcarcade.cabinet;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent behavioral checks: no Minecraft, provider callbacks, player seats or emulator. */
class WatchAuthorityTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static WatchLedger.Source source(long value) { return new WatchLedger.Source(id(value), id(value + 1000)); }
    private static WatchLedger.Candidate at(WatchLedger.Source source, double distanceSquared) { return new WatchLedger.Candidate(source, distanceSquared); }
    private static final class EqualConnection {
        @Override public boolean equals(Object other) { return other instanceof EqualConnection; }
        @Override public int hashCode() { return 1; }
    }
    @Test void nearestWinsRegardlessOfIterationOrderAndTiesAreStable() {
        var a=source(1); var b=source(2); var c=new Object();
        var ledger=new WatchLedger();
        assertEquals(b,ledger.select(id(100),c,List.of(at(a,25),at(b,4)),true,0).source());
        ledger.remove(id(100));
        assertEquals(b,ledger.select(id(100),c,List.of(at(b,4),at(a,25)),true,0).source());
        ledger.remove(id(100));
        assertEquals(a,ledger.select(id(100),c,List.of(at(b,4),at(a,4)),true,0).source());
    }
    @Test void admissionAndRetentionUseSeparateInclusiveBoundaries() {
        var ledger=new WatchLedger(); var c=new Object(); var a=source(1); var b=source(2);
        assertNull(ledger.select(id(1),c,List.of(at(a,256.001)),true,0));
        var old=ledger.select(id(1),c,List.of(at(a,256)),true,1);
        assertNotNull(old);
        assertSame(old,ledger.select(id(1),c,List.of(at(a,400),at(b,0)),true,2));
        var next=ledger.select(id(1),c,List.of(at(a,400.001),at(b,0)),true,3);
        assertEquals(b,next.source()); assertTrue(next.revision()>old.revision()); assertNotEquals(old.token(),next.token());
    }
    @Test void ineligibleOrMissingSourceRevokesWithoutCreatingAnyOtherAuthority() {
        var ledger=new WatchLedger(); var c=new Object(); var candidates=List.of(at(source(1),1));
        var old=ledger.select(id(1),c,candidates,true,0);
        assertNull(ledger.select(id(1),c,candidates,false,1)); assertNull(ledger.get(id(1)));
        assertNull(ledger.authorized(id(1),c,old.token(),old.revision(),1));
        assertNotNull(ledger.select(id(1),c,candidates,true,2));
        assertNull(ledger.select(id(1),c,List.of(),true,3)); assertTrue(ledger.all().isEmpty());
    }
    @Test void connectionIsIdentityNotEqualsOrOnlyPlayerUuid() {
        var ledger=new WatchLedger(); var oldConnection=new EqualConnection(); var newConnection=new EqualConnection();
        var candidates=List.of(at(source(1),0)); var old=ledger.select(id(1),oldConnection,candidates,true,0);
        assertEquals(oldConnection,newConnection); assertNotSame(oldConnection,newConnection);
        assertNull(ledger.authorized(id(1),newConnection,old.token(),old.revision(),1));
        assertFalse(ledger.heartbeat(id(1),newConnection,old.token(),old.revision(),1));
        assertNull(ledger.release(id(1),newConnection,old.token(),old.revision(),1)); assertSame(old,ledger.get(id(1)));
        var next=ledger.select(id(1),newConnection,candidates,true,2);
        assertTrue(next.revision()>old.revision()); assertNotEquals(old.token(),next.token());
        assertNull(ledger.authorized(id(1),oldConnection,old.token(),old.revision(),3));
        assertSame(next,ledger.authorized(id(1),newConnection,next.token(),next.revision(),3));
    }
    @Test void tokenRevisionAndPlayerMustAllMatchIncludingAfterReentry() {
        var ledger=new WatchLedger(); var c=new Object(); var candidates=List.of(at(source(1),0));
        var old=ledger.select(id(1),c,candidates,true,0);
        assertNull(ledger.authorized(id(2),c,old.token(),old.revision(),0));
        assertNull(ledger.authorized(id(1),c,id(999),old.revision(),0));
        assertNull(ledger.authorized(id(1),c,old.token(),old.revision()+1,0));
        assertSame(old,ledger.release(id(1),c,old.token(),old.revision(),1));
        assertFalse(ledger.heartbeat(id(1),c,old.token(),old.revision(),2));
        var next=ledger.select(id(1),c,candidates,true,3);
        assertNull(ledger.release(id(1),c,old.token(),old.revision(),4));
        assertNull(ledger.authorized(id(1),c,old.token(),next.revision(),4));
        assertNull(ledger.authorized(id(1),c,next.token(),old.revision(),4));
        assertSame(next,ledger.get(id(1)));
    }
    @Test void timeoutIsExclusiveHeartbeatExtendsButCannotResurrect() {
        var ledger=new WatchLedger(); var c=new Object(); var candidates=List.of(at(source(1),0));
        var lease=ledger.select(id(1),c,candidates,true,10);
        assertNotNull(ledger.authorized(id(1),c,lease.token(),lease.revision(),109));
        assertTrue(ledger.heartbeat(id(1),c,lease.token(),lease.revision(),109));
        assertNotNull(ledger.authorized(id(1),c,lease.token(),lease.revision(),208));
        assertNull(ledger.authorized(id(1),c,lease.token(),lease.revision(),209));
        assertFalse(ledger.heartbeat(id(1),c,lease.token(),lease.revision(),209));
        var replacement=ledger.select(id(1),c,candidates,true,210);
        assertNotEquals(lease.token(),replacement.token()); assertTrue(replacement.revision()>lease.revision());
    }
    @Test void scanDoesNotItselfExtendHeartbeatDeadline() {
        var ledger=new WatchLedger(); var c=new Object(); var candidates=List.of(at(source(1),0));
        var lease=ledger.select(id(1),c,candidates,true,0);
        for(int tick=10;tick<100;tick+=10)assertSame(lease,ledger.select(id(1),c,candidates,true,tick));
        assertNull(ledger.authorized(id(1),c,lease.token(),lease.revision(),100));
    }
    @Test void replacementHostAndCrossSourceUseGlobalIncreasingRevision() {
        var ledger=new WatchLedger(); var c=new Object(); var a=source(1);
        var first=ledger.select(id(1),c,List.of(at(a,0)),true,0);
        var next=ledger.select(id(1),c,List.of(at(new WatchLedger.Source(a.id(),id(990)),0)),true,1);
        assertTrue(next.revision()>first.revision()); assertNotEquals(first.token(),next.token());
        var other=ledger.select(id(2),new Object(),List.of(at(source(2),0)),true,1);
        assertTrue(other.revision()>next.revision());
        assertNull(ledger.authorized(id(1),c,first.token(),first.revision(),2));
    }
    @Test void fullSourceFallsBackToNextNearestAndFreedSeatIsReusable() {
        var ledger=new WatchLedger(); var a=source(1); var b=source(2);
        for(int i=0;i<8;i++)assertNotNull(ledger.select(id(100+i),new Object(),List.of(at(a,0)),true,0));
        assertEquals(8,ledger.count(a)); assertNull(ledger.select(id(999),new Object(),List.of(at(a,0)),true,1));
        assertEquals(b,ledger.select(id(999),new Object(),List.of(at(a,0),at(b,1)),true,1).source());
        ledger.remove(id(100)); assertNotNull(ledger.select(id(888),new Object(),List.of(at(a,0)),true,2));
        assertEquals(8,ledger.count(a)); assertEquals(9,ledger.all().size());
    }
    @Test void eightSharedSourcesHaveExactlySixtyFourPossibleViewers() {
        var ledger=new WatchLedger(); var candidates=new ArrayList<WatchLedger.Candidate>();
        for(int s=1;s<=8;s++)candidates.add(at(source(s),s));
        for(int i=0;i<64;i++)assertNotNull(ledger.select(id(100+i),new Object(),candidates,true,0));
        assertNull(ledger.select(id(999),new Object(),candidates,true,0)); assertEquals(64,ledger.all().size());
        for(int s=1;s<=8;s++)assertEquals(8,ledger.count(source(s)));
        assertThrows(UnsupportedOperationException.class,()->ledger.all().clear());
    }
    @Test void sourceRemovalIsExactToBothSourceAndHostLease() {
        var ledger=new WatchLedger(); var a=source(1); var newer=new WatchLedger.Source(a.id(),id(55));
        ledger.select(id(1),new Object(),List.of(at(a,0)),true,0);
        ledger.select(id(2),new Object(),List.of(at(newer,0)),true,0);
        var removed=ledger.removeSource(a); assertEquals(1,removed.size()); assertEquals(id(1),removed.getFirst().player());
        assertNull(ledger.get(id(1))); assertNotNull(ledger.get(id(2))); assertTrue(ledger.removeSource(a).isEmpty());
    }
    @Test void invalidCandidateDistancesAndOversizedSourceListsAreRejected() {
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->at(source(1),bad));
        var candidates=new ArrayList<WatchLedger.Candidate>(); for(int i=0;i<9;i++)candidates.add(at(source(i),0));
        assertThrows(IllegalArgumentException.class,()->new WatchLedger().select(id(1),new Object(),candidates,true,0));
        assertThrows(NullPointerException.class,()->new WatchLedger.Source(id(1),null));
    }
    @Test void budgetChargesWholeRecipientFramesAndNeverPartiallyConsumesFailedAttempt() {
        var budget=new WatchBudget(); int cap=WatchBudget.SOURCE_BYTES;
        assertTrue(budget.tryReserve(id(1),0,cap-1024));
        assertFalse(budget.tryReserve(id(1),0,1025)); assertTrue(budget.tryReserve(id(1),0,1024));
        assertFalse(budget.tryReserve(id(1),19,1)); assertTrue(budget.tryReserve(id(1),20,cap));
    }
    @Test void globalBudgetCountsEverySourceAndEveryRecipientCopy() {
        var budget=new WatchBudget();
        for(int s=1;s<=4;s++)for(int recipient=0;recipient<8;recipient++)assertTrue(budget.tryReserve(id(s),0,WatchBudget.SOURCE_BYTES/8));
        assertFalse(budget.tryReserve(id(5),0,1)); assertFalse(budget.tryReserve(id(1),19,1));
        assertTrue(budget.tryReserve(id(5),20,WatchBudget.SOURCE_BYTES));
    }
    @Test void rollingBudgetExpiresEachChargeAtItsOwnTick() {
        var budget=new WatchBudget(); int half=WatchBudget.SOURCE_BYTES/2;
        assertTrue(budget.tryReserve(id(1),0,half)); assertTrue(budget.tryReserve(id(1),10,half));
        assertFalse(budget.tryReserve(id(1),19,1)); assertTrue(budget.tryReserve(id(1),20,half));
        assertFalse(budget.tryReserve(id(1),29,1)); assertTrue(budget.tryReserve(id(1),30,half));
    }
    @Test void sourceRestartCannotRefundGlobalBytesAlreadySent() {
        var budget=new WatchBudget();
        for(int i=0;i<4;i++){assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));budget.remove(id(1));}
        assertFalse(budget.tryReserve(id(1),19,1)); assertTrue(budget.tryReserve(id(1),20,WatchBudget.SOURCE_BYTES));
    }
    @Test void budgetSourceTableIsBoundedAndRemovalAllowsReplacement() {
        var budget=new WatchBudget(); for(int i=0;i<8;i++)assertTrue(budget.tryReserve(id(i),0,1));
        assertFalse(budget.tryReserve(id(99),0,1)); budget.remove(id(0)); assertTrue(budget.tryReserve(id(99),0,1));
        assertFalse(budget.tryReserve(id(100),0,1));
    }
    @Test void invalidByteCountsCannotCreditEitherBudget() {
        var budget=new WatchBudget();
        for(int bad:new int[]{Integer.MIN_VALUE,-1,0,WatchBudget.SOURCE_BYTES+1,Integer.MAX_VALUE})assertFalse(budget.tryReserve(id(1),0,bad));
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES)); assertFalse(budget.tryReserve(id(1),0,1));
    }
    @Test void differentCandidateSetsCannotBypassGlobalEightSourceCap() {
        var ledger=new WatchLedger();
        for(int i=0;i<8;i++)assertNotNull(ledger.select(id(100+i),new Object(),List.of(at(source(i),0)),true,0));
        assertNull(ledger.select(id(999),new Object(),List.of(at(source(99),0)),true,0));
        assertEquals(8,ledger.all().size());ledger.remove(id(100));
        assertNotNull(ledger.select(id(999),new Object(),List.of(at(source(99),0)),true,0));
    }
    @Test void negativeTimeCannotRenewLeaseOrCreditBandwidth() {
        var ledger=new WatchLedger();var c=new Object();var candidates=List.of(at(source(1),0));
        assertThrows(IllegalArgumentException.class,()->ledger.select(id(1),c,candidates,true,-1));
        var lease=ledger.select(id(1),c,candidates,true,0);
        assertFalse(ledger.heartbeat(id(1),c,lease.token(),lease.revision(),-1));assertSame(lease,ledger.get(id(1)));
        var budget=new WatchBudget();assertFalse(budget.tryReserve(id(1),-1,1));assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));
    }
}
