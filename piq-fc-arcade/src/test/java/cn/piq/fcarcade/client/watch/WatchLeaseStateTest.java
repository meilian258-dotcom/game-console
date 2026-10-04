package cn.piq.fcarcade.client.watch;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchLeaseStateTest {
    @Test void tombstoneCannotBeRevivedByDelayedStartOrHeartbeat() {
        var state = new WatchLeaseState(); Object c = new Object(); UUID lease = UUID.randomUUID();
        assertFalse(state.begin(1, lease)); state.connection(c);
        assertTrue(state.begin(1, lease)); assertTrue(state.matches(1, lease));
        state.retire(); assertFalse(state.matches(1, lease)); assertFalse(state.begin(1, lease));
        assertTrue(state.begin(2, UUID.randomUUID())); assertFalse(state.stop(1, lease));
    }
    @Test void duplicateCannotReopenReceiverOrResetAssembler() {
        var state = new WatchLeaseState(); UUID lease = UUID.randomUUID(); state.connection(new Object());
        assertTrue(state.begin(4, lease)); assertFalse(state.begin(4, lease));
        assertTrue(state.matches(4, lease)); assertFalse(state.matches(4, UUID.randomUUID()));
        assertFalse(state.stop(4, UUID.randomUUID())); assertTrue(state.matches(4, lease));
        assertTrue(state.stop(4, lease)); assertFalse(state.matches(4, lease));
        assertFalse(state.begin(4, lease));
    }
    @Test void switchingHasConstantStateWithout512SessionLifetimeLimit() {
        var state = new WatchLeaseState(); state.connection(new Object());
        for (int i = 1; i <= 20_000; i++) {
            UUID lease = UUID.randomUUID(); assertTrue(state.begin(i, lease));
            assertTrue(state.matches(i, lease)); assertTrue(state.stop(i, lease));
        }
    }
    @Test void newConnectionHasIndependentRevisionAndNoOldLease() {
        var state = new WatchLeaseState(); Object a = new Object(), b = new Object(); UUID old = UUID.randomUUID();
        state.connection(a); assertTrue(state.begin(99, old)); assertFalse(state.connection(a));
        assertTrue(state.matches(99, old)); assertTrue(state.connection(b)); assertFalse(state.matches(99, old));
        UUID next = UUID.randomUUID(); assertTrue(state.begin(1, next));
        state.connection(null); assertFalse(state.begin(200, old)); assertFalse(state.matches(1, next));
    }
    @Test void stopBeforeStartLeavesATombstone() {
        var state = new WatchLeaseState(); state.connection(new Object()); UUID lease = UUID.randomUUID();
        assertTrue(state.stop(3, lease)); assertFalse(state.begin(3, lease));
        assertTrue(state.begin(4, UUID.randomUUID()));
    }
    @Test void fourSourcesHaveIndependentHeartbeatAndRetirement(){
        var state=new WatchLeaseState();state.connection(new Object());
        var leases=new UUID[4];
        for(int i=0;i<4;i++){leases[i]=UUID.randomUUID();assertTrue(state.begin("source"+i,i+1,leases[i]));}
        assertEquals(4,state.size());assertFalse(state.begin("fifth",5,UUID.randomUUID()));
        assertTrue(state.stop(2,leases[1]));
        for(int i:new int[]{0,2,3})assertTrue(state.matches(i+1,leases[i]));
        assertTrue(state.begin("fifth",5,UUID.randomUUID()));assertEquals(4,state.size());
        assertFalse(state.begin("source1",2,leases[1]));
    }
    @Test void sameSourceReplacementAndDelayedStopNeverCloseOtherSources(){
        var state=new WatchLeaseState();state.connection(new Object());
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),a2=UUID.randomUUID();
        assertTrue(state.begin("A",1,a));assertTrue(state.begin("B",2,b));
        assertTrue(state.begin("A",3,a2));assertEquals(2,state.size());
        assertFalse(state.matches(1,a));assertFalse(state.stop(1,a));
        assertTrue(state.matches(2,b));assertTrue(state.matches(3,a2));
        state.retire(a2);assertTrue(state.matches(2,b));assertFalse(state.begin("A",3,a2));
    }
    @Test void futureStopTombstoneDoesNotClearLiveOtherSources(){
        var state=new WatchLeaseState();state.connection(new Object());
        UUID a=UUID.randomUUID(),future=UUID.randomUUID();assertTrue(state.begin("A",1,a));
        assertTrue(state.stop(9,future));assertTrue(state.matches(1,a));
        assertFalse(state.begin("B",9,future));assertTrue(state.stop(1,a));
    }
    @Test void failedSourceDoesNotBlockNewConnectionOrUnrelatedLease(){
        var state=new WatchLeaseState();state.connection(new Object());
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();state.begin("A",7,a);state.begin("B",8,b);
        state.retire(a);assertTrue(state.matches(8,b));state.connection(new Object());
        assertEquals(0,state.size());assertTrue(state.begin("A",1,UUID.randomUUID()));
        assertFalse(state.matches(8,b));
    }
}
