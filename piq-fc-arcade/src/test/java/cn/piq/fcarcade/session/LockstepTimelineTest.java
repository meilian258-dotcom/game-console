package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LockstepTimelineTest {
    @Test
    void acceptedSnapshotsBoundLongSessionAndOldRequestsCannotRebaseHistory() {
        LockstepState state = new LockstepState();
        state.restart();
        LockstepTimeline timeline = new LockstepTimeline();
        timeline.reset(state.epoch());
        for (int tick = 1; tick <= 100_000; tick++) {
            state.acceptInput(java.util.UUID.nameUUIDFromBytes(new byte[]{1}), state.epoch(), 0, tick, tick % 2);
            for (int frame = 0; frame < 3; frame++) timeline.record(state.advanceFrame());
            if (tick % 80 == 0) timeline.discardThrough(state.targetFrame() - 30);
            org.junit.jupiter.api.Assertions.assertTrue(timeline.snapshot().size() <= 90);
        }
        long base = timeline.baseFrame();
        long target = timeline.targetFrame();
        assertEquals(target - base, timeline.snapshotAfter(base).stream().mapToLong(LockstepInputRun::frames).sum());
        assertThrows(IllegalArgumentException.class, () -> timeline.snapshotAfter(base - 3));
        assertThrows(IllegalArgumentException.class, () -> timeline.discardThrough(base - 3));
        assertEquals(base, timeline.baseFrame());
        timeline.discardThrough(target);
        assertEquals(java.util.List.of(), timeline.snapshotAfter(target));
        timeline.record(state.advanceFrame());
        assertEquals(target + 1, timeline.targetFrame());
    }

    @Test
    void pruningInsideCompressedRunKeepsAbsoluteFrameAndMasks() {
        LockstepTimeline timeline = new LockstepTimeline();
        timeline.reset(4);
        for (int frame = 1; frame <= 30; frame++) timeline.record(new LockstepState.FrameStep(4, frame, 1, 2));
        timeline.discardThrough(21);
        assertEquals(java.util.List.of(new LockstepInputRun(9, 1, 2)), timeline.snapshot());
        assertEquals(java.util.List.of(new LockstepInputRun(3, 1, 2)), timeline.snapshotAfter(27));
    }
    @Test
    void compressesAdjacentFramesWithTheSameInputs() {
        LockstepTimeline timeline = new LockstepTimeline();
        timeline.reset(4);

        for (int frame = 1; frame <= 9; frame++)
            timeline.record(new LockstepState.FrameStep(4, frame, frame <= 6 ? 1 : 4, 2));

        assertEquals(9, timeline.targetFrame());
        assertEquals(
                java.util.List.of(
                        new LockstepInputRun(6, 1, 2),
                        new LockstepInputRun(3, 4, 2)),
                timeline.snapshot());
        assertEquals(
                java.util.List.of(new LockstepInputRun(3, 4, 2)),
                timeline.snapshotAfter(6));
    }

    @Test
    void resetClearsHistoryAndRejectsGapsOrOldEpochs() {
        LockstepTimeline timeline = new LockstepTimeline();
        timeline.reset(1);
        timeline.record(new LockstepState.FrameStep(1, 1, 0, 0));
        timeline.reset(2);

        assertEquals(0, timeline.targetFrame());
        assertEquals(java.util.List.of(), timeline.snapshot());
        assertThrows(
                IllegalArgumentException.class,
                () -> timeline.record(new LockstepState.FrameStep(1, 3, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> timeline.record(new LockstepState.FrameStep(2, 6, 0, 0)));
    }

    @Test void oneFrameTapsRoundTripThroughHistoryAndSnapshotBoundary() {
        var timeline = new LockstepTimeline(); timeline.reset(1);
        for (int frame = 1; frame <= 6; frame++)
            timeline.record(new LockstepState.FrameStep(1, frame, frame % 2, 0));
        assertEquals(6, timeline.snapshot().size());
        assertEquals(java.util.List.of(new LockstepInputRun(1, 0, 0), new LockstepInputRun(1, 1, 0),
                new LockstepInputRun(1, 0, 0)), timeline.snapshotAfter(3));
        assertThrows(IllegalArgumentException.class, () -> timeline.snapshotAfter(1));
    }

    @Test void missingSnapshotsCannotGrowInputHistoryWithoutBound() {
        var timeline = new LockstepTimeline(); timeline.reset(1);
        for (int frame = 1; frame <= LockstepTimeline.MAX_RETAINED_FRAMES; frame++)
            timeline.record(new LockstepState.FrameStep(1, frame, frame % 2, 0));
        org.junit.jupiter.api.Assertions.assertFalse(timeline.canRecordFrames(1));
        assertThrows(IllegalArgumentException.class, () -> timeline.record(
                new LockstepState.FrameStep(1, LockstepTimeline.MAX_RETAINED_FRAMES + 1, 0, 0)));
        timeline.discardThrough(3);
        org.junit.jupiter.api.Assertions.assertTrue(timeline.canRecordFrames(3));
    }
}
