package cn.piq.retro.input;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static cn.piq.retro.input.GamepadState.Control.*;
import static cn.piq.retro.input.RetroButtons.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalInputSessionTest {
    private static final GamepadState.DeviceId PAD = new GamepadState.DeviceId("slot0:guid", 1);
    private static final GamepadState.DeviceId OTHER = new GamepadState.DeviceId("slot1:guid", 1);

    @Test void ownerUsesIdentityAndCannotStealOrClearAnotherSession() {
        var session = new LocalInputSession();
        Object owner = new String("equal"), impostor = new String("equal");
        arm(session, owner);
        session.keyboard(owner, A);
        assertFalse(session.acquire(impostor));
        assertFalse(session.release(impostor));
        assertFalse(session.clear(impostor));
        assertFalse(session.focus(impostor, false));
        assertFalse(session.selectDevice(impostor, OTHER));
        assertFalse(session.profile(impostor, InputProfile.defaults()));
        assertFalse(session.keyboard(impostor, 0));
        assertFalse(session.gamepad(impostor, GamepadState.disconnected(PAD)));
        assertEquals(0, session.current(impostor)); assertEquals(0, session.poll(impostor));
        assertEquals(A, session.current(owner));
        assertTrue(session.acquire(owner)); assertEquals(A, session.current(owner));
        assertTrue(session.release(owner)); assertTrue(session.acquire(impostor));
        assertEquals(0, session.current(impostor)); assertEquals(0, session.poll(impostor));
        assertFalse(session.keyboard(impostor, A)); // new owner starts unfocused
        assertThrows(NullPointerException.class, () -> session.acquire(null));
    }

    @Test void acquisitionAndFocusRecoveryRequireNeutralNotAButtonAlreadyHeld() {
        var session = new LocalInputSession(); Object owner = new Object();
        session.acquire(owner); session.selectDevice(owner, PAD); session.focus(owner, true);
        session.keyboard(owner, B); session.gamepad(owner, pad(SOUTH));
        assertEquals(0, session.current(owner));
        session.keyboard(owner, 0); session.gamepad(owner, GamepadState.neutral(PAD));
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        assertEquals(A | B, session.current(owner));
        session.focus(owner, false);
        assertEquals(0, session.current(owner)); assertEquals(0, session.poll(owner));
        session.keyboard(owner, 0); session.gamepad(owner, GamepadState.neutral(PAD)); // while unfocused: no rearm
        session.focus(owner, true);
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        assertEquals(0, session.current(owner));
        session.keyboard(owner, 0); session.gamepad(owner, GamepadState.neutral(PAD));
        session.gamepad(owner, pad(SOUTH));
        assertEquals(B, session.current(owner));
    }

    @Test void eitherSourceCanKeepAButtonHeldWhenTheOtherReleasesIt() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.keyboard(owner, B); session.gamepad(owner, pad(SOUTH));
        session.keyboard(owner, 0); assertEquals(B, session.current(owner));
        session.keyboard(owner, B); session.gamepad(owner, GamepadState.neutral(PAD));
        assertEquals(B, session.current(owner));
        session.keyboard(owner, 0); assertEquals(0, session.current(owner));
        assertEquals(B, session.poll(owner)); assertEquals(0, session.poll(owner));
    }

    @Test void mergingOppositesDoesNotDestroyTheRemainingHeldSource() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.keyboard(owner, UP | LEFT | B);
        session.gamepad(owner, pad(DPAD_DOWN, DPAD_RIGHT));
        assertEquals(B, session.current(owner));
        session.gamepad(owner, GamepadState.neutral(PAD));
        assertEquals(UP | LEFT | B, session.current(owner));
        session.keyboard(owner, UP | DOWN | B); assertEquals(B, session.current(owner));
        session.keyboard(owner, UP | B); assertEquals(UP | B, session.current(owner));
    }

    @Test void fastPressAndReleaseEdgesSurviveUntilEmulatorPolling() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        for (int i = 0; i < 50; i++) { session.gamepad(owner, pad(SOUTH)); session.gamepad(owner, GamepadState.neutral(PAD)); }
        assertEquals(0, session.current(owner)); assertEquals(100, session.queuedEdges(owner));
        for (int i = 0; i < 50; i++) { assertEquals(B, session.poll(owner)); assertEquals(0, session.poll(owner)); }
        assertEquals(0, session.poll(owner));
        session.keyboard(owner, A); assertEquals(A, session.poll(owner));
        assertEquals(A, session.poll(owner)); assertEquals(A, session.poll(owner));
    }

    @Test void disconnectImmediatelyFlushesPadEdgesButPreservesLiveKeyboard() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        session.gamepad(owner, GamepadState.disconnected(PAD));
        assertEquals(A, session.current(owner)); assertEquals(A, session.poll(owner));
        assertEquals(0, session.queuedEdges(owner)); assertFalse(session.gamepadArmed(owner));
        session.gamepad(owner, pad(SOUTH)); assertEquals(A, session.current(owner));
        session.gamepad(owner, GamepadState.neutral(PAD));
        session.gamepad(owner, pad(SOUTH)); assertEquals(A | B, session.current(owner));
    }

    @Test void selectedDeviceAndGenerationIgnoreStaleOrOtherDeviceEvents() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.gamepad(owner, pad(SOUTH));
        assertFalse(session.gamepad(owner, GamepadState.disconnected(OTHER)));
        assertEquals(B, session.current(owner));
        session.keyboard(owner, A);
        var nextGeneration = new GamepadState.DeviceId(PAD.id(), 2);
        session.selectDevice(owner, nextGeneration);
        assertEquals(A, session.current(owner)); assertEquals(A, session.poll(owner));
        assertFalse(session.gamepad(owner, pad(SOUTH)));
        assertFalse(session.gamepad(owner, GamepadState.disconnected(PAD)));
        session.gamepad(owner, new GamepadState(nextGeneration, true, SOUTH.mask(), 0, 0, 0, 0, 0, 0));
        assertEquals(A, session.current(owner));
        session.gamepad(owner, GamepadState.neutral(nextGeneration));
        session.gamepad(owner, new GamepadState(nextGeneration, true, SOUTH.mask(), 0, 0, 0, 0, 0, 0));
        assertEquals(A | B, session.current(owner));
        session.selectDevice(owner, null);
        assertEquals(A, session.current(owner));
        assertFalse(session.gamepad(owner, GamepadState.neutral(nextGeneration)));
    }

    @Test void sameDeviceSelectionDoesNotClearInputsOrQueue() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.gamepad(owner, pad(SOUTH));
        session.selectDevice(owner, new GamepadState.DeviceId(PAD.id(), PAD.generation()));
        assertTrue(session.gamepadArmed(owner)); assertEquals(B, session.current(owner));
        assertEquals(B, session.poll(owner));
    }

    @Test void rearmIncludesUnboundButtonsRightStickAndTriggers() {
        var session = new LocalInputSession(); Object owner = new Object();
        session.acquire(owner); session.selectDevice(owner, PAD); session.focus(owner, true);
        session.gamepad(owner, pad(GUIDE)); assertFalse(session.gamepadArmed(owner));
        session.gamepad(owner, analog(0, 0, 1, 0, 0, 0)); assertFalse(session.gamepadArmed(owner));
        session.gamepad(owner, analog(0, 0, 0, 0, 1, 0)); assertFalse(session.gamepadArmed(owner));
        session.gamepad(owner, analog(0, 0, 0, 0, 0, 1)); assertFalse(session.gamepadArmed(owner));
        session.gamepad(owner, analog(.1f, .1f, -.1f, .1f, .1f, .1f)); assertTrue(session.gamepadArmed(owner));
        session.gamepad(owner, pad(SOUTH)); assertEquals(B, session.current(owner));
    }

    @Test void analogControlsCanBeRemappedAndProfileChangesRequireRelease() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        var profile = InputProfile.defaults().with(Button.B, Set.of(LEFT_TRIGGER))
                .with(Button.X, Set.of(RIGHT_STICK_UP)).with(Button.R, Set.of(RIGHT_TRIGGER));
        session.profile(owner, profile);
        assertEquals(A, session.current(owner)); assertEquals(A, session.poll(owner));
        session.gamepad(owner, analog(0, 0, 0, -1, 1, 1)); assertEquals(A, session.current(owner));
        session.gamepad(owner, GamepadState.neutral(PAD));
        session.gamepad(owner, analog(0, 0, 0, -1, 1, 1)); assertEquals(A | B | X | R, session.current(owner));
        session.gamepad(owner, analog(.5f, -.5f, 0, 0, .4f, .4f)); assertEquals(A | B | R | UP | RIGHT, session.current(owner));
        session.gamepad(owner, GamepadState.neutral(PAD)); assertEquals(A, session.current(owner));
    }

    @Test void clearForMenuOrPauseDropsQueuedPressesAndRequiresRelease() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        session.clear(owner);
        assertEquals(0, session.poll(owner)); assertEquals(0, session.queuedEdges(owner));
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        assertEquals(0, session.current(owner));
        session.keyboard(owner, 0); session.gamepad(owner, GamepadState.neutral(PAD));
        session.keyboard(owner, A); session.gamepad(owner, pad(SOUTH));
        assertEquals(A | B, session.current(owner));
    }

    @Test void boundedQueueOverflowFailsReleasedInsteadOfReplayingOrSticking() {
        var session = new LocalInputSession(); Object owner = new Object(); arm(session, owner);
        for (int i = 0; i < LocalInputSession.MAX_QUEUED_EDGES; i++) session.keyboard(owner, (i & 1) == 0 ? B : 0);
        assertEquals(LocalInputSession.MAX_QUEUED_EDGES, session.queuedEdges(owner));
        session.keyboard(owner, B);
        assertEquals(1, session.overflowCount()); assertEquals(0, session.current(owner));
        assertEquals(0, session.poll(owner)); assertEquals(0, session.queuedEdges(owner));
        session.gamepad(owner, pad(SOUTH)); session.keyboard(owner, B); assertEquals(0, session.current(owner));
        session.keyboard(owner, 0); session.keyboard(owner, A); assertEquals(A, session.current(owner));
    }

    @Test void keyboardOnlyModeDoesNotNeedASelectedOrConnectedPad() {
        var session = new LocalInputSession(); Object owner = new Object();
        session.acquire(owner); session.focus(owner, true); session.keyboard(owner, 0);
        session.keyboard(owner, A | UP); assertEquals(A | UP, session.current(owner));
        assertFalse(session.gamepad(owner, GamepadState.neutral(PAD)));
        assertFalse(session.focus(null, true)); assertEquals(0, session.current(null));
    }

    private static void arm(LocalInputSession session, Object owner) {
        assertTrue(session.acquire(owner)); assertTrue(session.selectDevice(owner, PAD));
        assertTrue(session.focus(owner, true)); session.keyboard(owner, 0);
        session.gamepad(owner, GamepadState.neutral(PAD)); assertTrue(session.gamepadArmed(owner));
    }

    private static GamepadState pad(GamepadState.Control... buttons) {
        return new GamepadState(PAD, true, GamepadState.buttons(buttons), 0, 0, 0, 0, 0, 0);
    }

    private static GamepadState analog(float lx, float ly, float rx, float ry, float lt, float rt) {
        return new GamepadState(PAD, true, 0, lx, ly, rx, ry, lt, rt);
    }
}
