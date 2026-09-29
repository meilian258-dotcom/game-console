package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetSyncState;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSyncUploadsTest {
    private static CabinetSyncWorker.Event snapshot(long frame){byte[] state={(byte)frame};return new CabinetSyncWorker.Event(CabinetSyncWorker.Event.SNAPSHOT,frame,CabinetSyncState.hash(state),"",0,null,state);}
    @Test void refusedOfferRetriesSameTokenWithoutRenewingDeadline(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(10);long deadline=first.deadline;
        assertTrue(first.needsOffer(10));first.offered(10);assertFalse(first.needsOffer(49));assertTrue(first.needsOffer(50));
        first.offered(50);assertSame(first,q.current(50));assertEquals(deadline,first.deadline);assertEquals(1010,deadline);
        assertFalse(first.needsOffer(1010));
    }
    @Test void lostOfferGrantCanBeRetriedForLongerThanOldFiveSecondWindow(){
        var q=new CabinetSyncUploads();q.offer(snapshot(1800));var first=q.current(0);
        for(int tick=0;tick<=880;tick+=40){assertSame(first,q.current(tick));assertTrue(first.needsOffer(tick));first.offered(tick);}
        q.grant(first.token,899);assertTrue(first.granted);assertFalse(first.needsOffer(900));assertEquals(1000,first.deadline);
    }
    @Test void pendingCheckpointKeepsOnlyNewestWithoutReplacingGrantedTransfer(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(0);first.offered(0);q.grant(first.token,1);
        for(int frame=1800;frame<=18000;frame+=1800)q.offer(snapshot(frame));
        assertSame(first,q.current(5));assertTrue(first.granted);assertEquals(0,first.frame);
        first.offset=first.state.length;q.complete(first);var next=q.current(10);assertEquals(18000,next.frame);assertNotEquals(first.token,next.token);
    }
    @Test void expiredTokenCannotBeRevivedByLateGrant(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(0);first.offered(0);q.offer(snapshot(1800));
        var next=q.current(1000);assertNotEquals(first.token,next.token);next.offered(1000);
        q.grant(first.token,1001);assertFalse(next.granted);q.grant(next.token,1001);assertTrue(next.granted);
    }
    @Test void noNewSnapshotAtTotalDeadlineFailsClosedInsteadOfEndlessRetry(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));q.current(20).offered(20);
        assertThrows(IllegalStateException.class,()->q.current(1020));
    }
    @Test void grantRequiresOfferedCurrentTokenAndUnexpiredDeadline(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(0);
        q.grant(first.token,0);assertFalse(first.granted);first.offered(0);
        q.grant(UUID.randomUUID(),1);assertFalse(first.granted);q.grant(first.token,1000);assertFalse(first.granted);
    }
    @Test void partialOrUnrelatedCompletionCannotDiscardCurrent(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(0);first.offered(0);q.grant(first.token,1);
        q.complete(first);assertSame(first,q.current(2));
        var other=new CabinetSyncUploads();other.offer(snapshot(0));q.complete(other.current(0));assertSame(first,q.current(3));
        first.offset=1;q.complete(first);assertNull(q.current(4));
    }
    @Test void staleWorkerSnapshotCannotReplaceNewerCheckpoint(){
        var q=new CabinetSyncUploads();q.offer(snapshot(1800));q.offer(snapshot(300));q.offer(snapshot(1800));
        assertEquals(1800,q.current(0).frame);
    }
    @Test void closeDropsBothArraysAndRejectsLateEventsAndGrants(){
        var q=new CabinetSyncUploads();q.offer(snapshot(0));var first=q.current(0);first.offered(0);q.offer(snapshot(1800));q.close();
        q.grant(first.token,1);q.offer(snapshot(3600));assertNull(q.current(2000));assertFalse(first.granted);
    }
    @Test void invalidEventsCannotEnterMailbox(){
        var q=new CabinetSyncUploads();assertThrows(IllegalArgumentException.class,()->q.offer(null));
        assertThrows(IllegalArgumentException.class,()->q.offer(new CabinetSyncWorker.Event(CabinetSyncWorker.Event.DIGEST,300,"0".repeat(64),"",0,null,new byte[]{1})));
        assertThrows(IllegalArgumentException.class,()->q.offer(new CabinetSyncWorker.Event(CabinetSyncWorker.Event.SNAPSHOT,0,"bad","",0,null,new byte[]{1})));
        assertNull(q.current(0));
    }
}
