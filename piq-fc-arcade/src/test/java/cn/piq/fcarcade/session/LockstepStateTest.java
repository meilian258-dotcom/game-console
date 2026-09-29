package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockstepStateTest {
    @Test void twoTapsInOneServerTickKeepBothPressesAndReleases() {
        var state = new LockstepState(); state.restart(); var id = UUID.randomUUID();
        int seq = 0;
        for (int mask : new int[]{1, 0, 1, 0}) assertTrue(state.acceptInput(id, 1, 0, seq++, mask));
        for (int mask : new int[]{1, 0, 1, 0}) assertEquals(mask, state.advanceFrame().playerOneMask());
    }
    @Test void playerTwoEdgesRemainIndependentOfPlayerOneHeldMask() {
        var state = new LockstepState(); state.restart(); var p1 = UUID.randomUUID(); var p2 = UUID.randomUUID();
        state.acceptInput(p1, 1, 0, 0, 128);
        state.acceptInput(p2, 1, 1, 0, 1); state.acceptInput(p2, 1, 1, 1, 0);
        var down = state.advanceFrame(); var up = state.advanceFrame();
        assertEquals(128, down.playerOneMask()); assertEquals(128, up.playerOneMask());
        assertEquals(1, down.playerTwoMask()); assertEquals(0, up.playerTwoMask());
    }
    @Test void forceReleaseIsImmediateButStillRequiresCurrentEpochSequenceAndNeutralMask() {
        var state = new LockstepState(); state.restart(); var id = UUID.randomUUID();
        state.acceptInput(id, 1, 0, 0, 1); state.acceptInput(id, 1, 0, 1, 0);
        assertFalse(state.acceptInput(id, 0, 0, 2, 0, true, false));
        assertFalse(state.acceptInput(id, 1, 0, 1, 0, true, false));
        assertFalse(state.acceptInput(id, 1, 0, 2, 1, true, false));
        assertEquals(1, state.advanceFrame().playerOneMask());
        state.acceptInput(id, 1, 0, 2, 2);
        assertTrue(state.acceptInput(id, 1, 0, 3, 0, true, true));
        assertEquals(0, state.advanceFrame().playerOneMask()); assertEquals(0, state.advanceFrame().playerOneMask());
    }
    @Test void rateLimitCannotLeaveKeyDownOrAllowOldSequencesToRearm() {
        var state = new LockstepState(); state.restart(); var id = UUID.randomUUID();
        state.acceptInput(id, 1, 0, 0, 1); state.advanceFrame();
        assertFalse(state.acceptInput(id, 1, 0, 1, 0, false, true));
        assertEquals(0, state.advanceFrame().playerOneMask());
        assertFalse(state.acceptInput(id, 1, 0, 1, 1));
        assertFalse(state.acceptInput(id, 1, 0, 2, 1));
        assertTrue(state.acceptInput(id, 1, 0, 3, 0));
        assertTrue(state.acceptInput(id, 1, 0, 4, 1)); assertEquals(1, state.advanceFrame().playerOneMask());
    }
    @Test void controllerDepartureAndEpochRestartDiscardAllUnexecutedEdges() {
        var state = new LockstepState(); state.restart(); var id = UUID.randomUUID();
        state.acceptInput(id, 1, 0, 0, 1); state.acceptInput(id, 1, 0, 1, 0);
        state.clearController(0); assertEquals(0, state.advanceFrame().playerOneMask());
        state.acceptInput(id, 1, 0, 2, 2); state.restart();
        assertEquals(0, state.advanceFrame().playerOneMask());
        assertFalse(state.acceptInput(id, 1, 0, 3, 2));
    }
    @org.junit.jupiter.api.Test
    void reconnectRestartsOnlyDepartedPlayersInputSequence() {
        LockstepState state = new LockstepState();
        java.util.UUID returning = java.util.UUID.randomUUID();
        java.util.UUID remaining = java.util.UUID.randomUUID();
        state.restart();
        org.junit.jupiter.api.Assertions.assertTrue(state.acceptInput(returning, state.epoch(), 0, 1000, 1));
        org.junit.jupiter.api.Assertions.assertTrue(state.acceptInput(remaining, state.epoch(), 1, 2000, 2));
        state.forgetPlayer(returning);
        state.clearInputs();
        org.junit.jupiter.api.Assertions.assertTrue(state.acceptInput(returning, state.epoch(), 0, 0, 4));
        org.junit.jupiter.api.Assertions.assertFalse(state.acceptInput(remaining, state.epoch(), 1, 0, 8));
        org.junit.jupiter.api.Assertions.assertTrue(state.acceptInput(remaining, state.epoch(), 1, 2001, 16));
        var step = state.advanceFrame();
        org.junit.jupiter.api.Assertions.assertEquals(4, step.playerOneMask());
        org.junit.jupiter.api.Assertions.assertEquals(16, step.playerTwoMask());
    }
    @Test
    void advancesOneNesFramePerBroadcast() {
        LockstepState state = new LockstepState();
        state.restart();

        LockstepState.FrameStep first = state.advanceFrame();
        LockstepState.FrameStep second = state.advanceFrame();

        assertEquals(1, first.epoch());
        assertEquals(1, first.targetFrame());
        assertEquals(2, second.targetFrame());
    }

    @Test
    void acceptsOnlyCurrentEpochAndIncreasingSequences() {
        LockstepState state = new LockstepState();
        UUID player = UUID.randomUUID();
        state.restart();

        assertTrue(state.acceptInput(player, 1, 0, 0, 0x81));
        assertFalse(state.acceptInput(player, 1, 0, 0, 0x01));
        assertFalse(state.acceptInput(player, 0, 0, 1, 0x01));
        assertFalse(state.acceptInput(player, 1, 2, 1, 0x01));
        assertFalse(state.acceptInput(player, 1, 0, 1, 0x100));

        LockstepState.FrameStep step = state.advanceFrame();
        assertEquals(0x81, step.playerOneMask());
        assertEquals(0, step.playerTwoMask());
    }

    @Test
    void restartClearsFramesInputsAndSequenceHistory() {
        LockstepState state = new LockstepState();
        UUID player = UUID.randomUUID();
        state.restart();
        assertTrue(state.acceptInput(player, 1, 1, 5, 0x42));
        state.advanceFrame();

        state.restart();

        assertEquals(2, state.epoch());
        assertTrue(state.acceptInput(player, 2, 1, 0, 0x24));
        LockstepState.FrameStep step = state.advanceFrame();
        assertEquals(1, step.targetFrame());
        assertEquals(0, step.playerOneMask());
        assertEquals(0x24, step.playerTwoMask());
    }

    @Test
    void clearInputsKeepsFrameAndEpochWhileReleasingBothControllers() {
        LockstepState state = new LockstepState();
        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();
        state.restart();
        assertTrue(state.acceptInput(playerOne, 1, 0, 0, 0x81));
        assertTrue(state.acceptInput(playerTwo, 1, 1, 0, 0x42));
        state.advanceFrame();

        state.clearInputs();
        LockstepState.FrameStep step = state.advanceFrame();

        assertEquals(1, step.epoch());
        assertEquals(2, step.targetFrame());
        assertEquals(0, step.playerOneMask());
        assertEquals(0, step.playerTwoMask());
    }
}
