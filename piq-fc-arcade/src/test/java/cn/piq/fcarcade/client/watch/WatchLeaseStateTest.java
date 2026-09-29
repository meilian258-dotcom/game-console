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
}
