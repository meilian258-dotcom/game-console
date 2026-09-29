package cn.piq.flashbox.client;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlashWorldInputStateTest {
    private static final int W=87, A=65, D=68, S=83, SHIFT=340, UP=265, LEFT=263, SPACE=32, ESC=256;
    private FlashWorldInputState armed() {
        var state = new FlashWorldInputState();
        state.begin(Set.of()); state.sample(Set.of(), Set.of()); return state;
    }

    @Test void inactiveDoesNotCaptureAnyWorldInput() {
        var state = new FlashWorldInputState();
        assertFalse(state.key(W, 1)); assertFalse(state.mouse(1, 1)); assertFalse(state.active());
        assertEquals(new FlashWorldInputState.Masks(0, 0), state.masks());
    }
    @Test void entryRequiresReleaseOfEveryKeyNotOnlyGameKeys() {
        var state = new FlashWorldInputState(); state.begin(Set.of(75));
        state.sample(Set.of(75), Set.of()); assertFalse(state.armed());
        state.key(W, 1); assertEquals(0, state.masks().p2());
        state.sample(Set.of(), Set.of()); assertTrue(state.armed());
    }
    @Test void entryClickMustBeReleasedBeforeArming() {
        var state = new FlashWorldInputState(); state.begin(Set.of());
        state.sample(Set.of(), Set.of(0)); assertFalse(state.armed());
        state.sample(Set.of(), Set.of()); assertTrue(state.armed());
    }
    @Test void twoPortsAreIndependentFiveBitMasks() {
        var state = armed();
        for (int key : new int[]{UP, LEFT, SPACE, W, D, SHIFT}) assertTrue(state.key(key, 1));
        assertEquals(new FlashWorldInputState.Masks(21, 22), state.masks());
        state.key(W, 0); assertEquals(new FlashWorldInputState.Masks(21, 18), state.masks());
    }
    @Test void unrelatedHotkeysAreCapturedButNotSentToFlash() {
        var state = armed(); assertTrue(state.key(75, 1)); assertTrue(state.key(292, 1));
        assertEquals(new FlashWorldInputState.Masks(0, 0), state.masks());
    }
    @Test void repeatedKeyDoesNotChangeItsMask() {
        var state = armed(); state.key(A, 1); var before = state.masks();
        state.key(A, 2); state.key(A, 2); assertEquals(before, state.masks());
        state.key(A, 0); assertEquals(0, state.masks().p2());
    }
    @Test void exitImmediatelyClearsGameInput() {
        var state = armed(); state.key(W, 1); state.end(Set.of(W, ESC), Set.of());
        assertFalse(state.active()); assertFalse(state.armed());
        assertEquals(new FlashWorldInputState.Masks(0, 0), state.masks());
    }
    @Test void heldExitKeyAndDirectionStayQuarantinedUntilTheirRelease() {
        var state = armed(); state.key(W, 1); state.end(Set.of(W, ESC), Set.of());
        assertTrue(state.key(W, 2)); assertTrue(state.key(ESC, 0)); assertTrue(state.key(W, 0));
        assertFalse(state.key(W, 1)); assertFalse(state.draining());
    }
    @Test void unrelatedFreshWorldKeysWorkWhileOldKeysDrain() {
        var state = armed(); state.end(Set.of(W), Set.of());
        assertFalse(state.key(D, 1)); assertTrue(state.key(W, 2));
    }
    @Test void rightClickToPreviewDoesNotLeakReleaseOrWorldInteraction() {
        var state = armed(); assertTrue(state.mouse(1, 1)); state.end(Set.of(), Set.of(1));
        assertTrue(state.mouse(1, 0)); assertFalse(state.mouse(1, 1));
    }
    @Test void physicalReconciliationClearsMissedReleasesWithoutReactivating() {
        var state = armed(); state.end(Set.of(W), Set.of(0));
        state.sample(Set.of(), Set.of());
        assertFalse(state.draining()); assertFalse(state.active()); assertFalse(state.armed());
    }
    @Test void explicitReentryMustRearmAndNeverRevivesOldInput() {
        var state = armed(); state.key(S, 1); state.end(Set.of(S), Set.of());
        state.begin(Set.of(S)); state.sample(Set.of(S), Set.of());
        assertFalse(state.armed()); assertEquals(0, state.masks().p2());
        state.sample(Set.of(), Set.of()); state.key(W, 1); assertEquals(4, state.masks().p2());
    }
    @Test void heldControlOrAltCannotLeakNewCombinationShortcuts() {
        for (int modifier : new int[]{341, 342, 345, 346}) {
            var state = armed(); state.end(Set.of(modifier), Set.of());
            assertTrue(state.key(75, 1)); assertTrue(state.key(modifier, 0));
            assertTrue(state.key(75, 0)); assertFalse(state.key(75, 1));
        }
    }
    @Test void heldShiftCannotLeakModifiedMouseClicks() {
        var state = armed(); state.end(Set.of(SHIFT), Set.of());
        assertTrue(state.mouse(0, 1)); assertTrue(state.key(SHIFT, 0));
        assertTrue(state.mouse(0, 0)); assertFalse(state.mouse(0, 1));
    }
    @Test void heldF3CannotTriggerDebugCombinationOnExit() {
        var state = armed(); state.end(Set.of(292), Set.of());
        assertTrue(state.key(67, 1)); assertTrue(state.key(292, 0));
        assertTrue(state.key(67, 0)); assertFalse(state.key(67, 1));
    }
    @Test void focusLossSyntheticReleasesDoNotProvePhysicalRelease() {
        var state = armed(); state.key(W, 1); state.end(Set.of(W), Set.of(0), true);
        assertTrue(state.key(W, 0, false)); assertTrue(state.mouse(0, 0, false));
        state.sample(Set.of(), Set.of()); assertTrue(state.draining());
        assertTrue(state.key(W, 1)); assertTrue(state.key(W, 0));
        assertTrue(state.mouse(0, 0)); state.sample(Set.of(), Set.of());
        assertFalse(state.draining()); assertFalse(state.active());
    }
}
