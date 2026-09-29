package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTransactionTest {
    @Test void deniedProtectionCannotDeleteSaveOrOpenSession() {
        var gate = new InteractionTransaction(); var save = new AtomicBoolean(true); var session = new AtomicBoolean();
        assertFalse(gate.run(() -> true, () -> false, () -> {save.set(false);session.set(true);}));
        assertTrue(save.get()); assertFalse(session.get());
    }
    @Test void allowedCurrentActorCanCommitExactlyOnce() {
        var gate = new InteractionTransaction(); var commits = new AtomicInteger(); var order = new StringBuilder();
        assertTrue(gate.run(() -> {order.append('F');return true;}, () -> {order.append('P');return true;}, () -> {order.append('C');commits.incrementAndGet();}));
        assertEquals("FPFC", order.toString()); assertEquals(1, commits.get());
    }
    @Test void invalidInitialConnectionDoesNotEvenAskProtection() {
        var calls = new AtomicInteger(); var gate = new InteractionTransaction();
        assertFalse(gate.run(() -> false, () -> {calls.incrementAndGet();return true;}, calls::incrementAndGet));
        assertEquals(0, calls.get());
    }
    @Test void replacingConnectionDuringCallbackRejectsEvenSamePlayerId() {
        var original = new Object(); var source = new AtomicReference<>(original); var commits = new AtomicInteger();
        assertFalse(new InteractionTransaction().run(() -> source.get() == original, () -> {source.set(new Object());return true;}, commits::incrementAndGet));
        assertEquals(0, commits.get());
    }
    @Test void replacedEndpointOrHardwareIdentityCannotCommit() {
        for (int changed = 0; changed < 5; changed++) {
            Object[] identity = {new Object(),new Object(),new Object(),new Object(),new Object()};
            Object[] expected = identity.clone(); int index = changed; var commits = new AtomicInteger();
            assertFalse(new InteractionTransaction().run(() -> java.util.Arrays.equals(identity,expected), () -> {identity[index]=new Object();return true;}, commits::incrementAndGet));
            assertEquals(0, commits.get());
        }
    }
    @Test void permissionCallbackCannotReenterAnotherDeletion() {
        var gate = new InteractionTransaction(); var commits = new AtomicInteger();
        assertTrue(gate.run(() -> true, () -> {
            assertTrue(gate.active()); assertFalse(gate.run(() -> true, () -> true, commits::incrementAndGet)); return true;
        }, commits::incrementAndGet));
        assertEquals(1, commits.get()); assertFalse(gate.active());
    }
    @Test void permissionExceptionDoesNotCommitAndDoesNotLeaveGateLocked() {
        var gate = new InteractionTransaction(); var commits = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> gate.run(() -> true, () -> {throw new IllegalStateException("protection failed");}, commits::incrementAndGet));
        assertEquals(0, commits.get()); assertFalse(gate.active());
        assertTrue(gate.run(() -> true, () -> true, commits::incrementAndGet)); assertEquals(1, commits.get());
    }
    @Test void commitExceptionAlsoReleasesTheGuard() {
        var gate = new InteractionTransaction();
        assertThrows(IllegalArgumentException.class, () -> gate.run(() -> true, () -> true, () -> {throw new IllegalArgumentException();}));
        assertFalse(gate.active());
    }
    @Test void transferredWireRequiresNewActorsPermissionOnFirstEndpoint() {
        var gate = new InteractionTransaction(); var cables = new AtomicInteger(1); var links = new AtomicInteger();
        boolean currentEndAllowed = true, firstEndAllowedForReceiver = false;
        assertFalse(gate.run(() -> true, () -> currentEndAllowed && firstEndAllowedForReceiver,
                () -> {cables.decrementAndGet();links.incrementAndGet();}));
        assertEquals(1, cables.get()); assertEquals(0, links.get());
    }
    @Test void transferredWireIsNotDisabledWhenBothEndpointsAllowReceiver() {
        var gate = new InteractionTransaction(); var cables = new AtomicInteger(1); var links = new AtomicInteger();
        assertTrue(gate.run(() -> true, () -> true, () -> {cables.decrementAndGet();links.incrementAndGet();}));
        assertEquals(0, cables.get()); assertEquals(1, links.get());
    }
    @Test void changedWireOrClaimedSocketAfterPermissionPreservesBothItems() {
        var wire = new AtomicReference<>(new Object()); Object original = wire.get(); var commits = new AtomicInteger();
        assertFalse(new InteractionTransaction().run(() -> wire.get() == original, () -> {wire.set(new Object());return true;}, commits::incrementAndGet));
        assertEquals(0, commits.get());
    }
}
