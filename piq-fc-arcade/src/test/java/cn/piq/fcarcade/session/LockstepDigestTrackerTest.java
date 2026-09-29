package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LockstepDigestTrackerTest {
    @Test
    void observerFirstCannotMakeMatchingControllersMismatchAndOnlyObserverIsRecovered() {
        LockstepDigestTracker tracker = new LockstepDigestTracker();
        tracker.reset(1);
        UUID viewer = UUID.randomUUID();
        Set<UUID> members = Set.of(playerOne, playerTwo, viewer);
        assertEquals(LockstepDigestTracker.Result.WAITING,
                tracker.compareToReference(viewer, playerOne, 1, 300, 300, 99, members).result());
        var first = tracker.compareToReference(playerOne, playerOne, 1, 300, 300, 42, members);
        assertEquals(Set.of(viewer), first.mismatches());
        assertEquals(Set.of(), tracker.compareToReference(playerTwo, playerOne, 1, 300, 303, 42, members).mismatches());
        assertEquals(Set.of(), tracker.compareToReference(viewer, playerOne, 1, 300, 306, 100, members).mismatches());
    }

    @Test
    void lateObserverAndWrongSecondControllerAreIndependentlyComparedAgainstReference() {
        LockstepDigestTracker tracker = new LockstepDigestTracker();
        tracker.reset(1);
        UUID viewer = UUID.randomUUID();
        Set<UUID> members = Set.of(playerOne, playerTwo, viewer);
        tracker.compareToReference(playerOne, playerOne, 1, 300, 300, 42, members);
        assertEquals(Set.of(playerTwo), tracker.compareToReference(playerTwo, playerOne, 1, 300, 303, 43, members).mismatches());
        assertEquals(Set.of(viewer), tracker.compareToReference(viewer, playerOne, 1, 300, 306, 99, members).mismatches());
        assertEquals(LockstepDigestTracker.Result.REJECTED,
                tracker.compareToReference(UUID.randomUUID(), playerOne, 1, 600, 600, 42, members).result());
        assertEquals(LockstepDigestTracker.Result.REJECTED,
                tracker.compareToReference(viewer, playerOne, 1, 300, 903, 42, members).result());
    }
    private final UUID playerOne = UUID.randomUUID();
    private final UUID playerTwo = UUID.randomUUID();
    private final Set<UUID> players = Set.of(playerOne, playerTwo);

    @Test
    void matchesTwoReportsAtTheSameCheckpoint() {
        LockstepDigestTracker tracker = new LockstepDigestTracker();
        tracker.reset(2);

        assertEquals(
                LockstepDigestTracker.Result.WAITING,
                tracker.submit(playerOne, 2, 300, 300, 42, players));
        assertEquals(
                LockstepDigestTracker.Result.MATCH,
                tracker.submit(playerTwo, 2, 300, 303, 42, players));
    }

    @Test
    void detectsDifferentReportsAtTheSameCheckpoint() {
        LockstepDigestTracker tracker = new LockstepDigestTracker();
        tracker.reset(1);

        tracker.submit(playerOne, 1, 300, 300, 10, players);
        assertEquals(
                LockstepDigestTracker.Result.MISMATCH,
                tracker.submit(playerTwo, 1, 300, 300, 11, players));
    }

    @Test
    void rejectsWrongEpochDuplicateAndStaleReports() {
        LockstepDigestTracker tracker = new LockstepDigestTracker();
        tracker.reset(3);

        assertEquals(
                LockstepDigestTracker.Result.REJECTED,
                tracker.submit(playerOne, 2, 300, 300, 1, players));
        assertEquals(
                LockstepDigestTracker.Result.WAITING,
                tracker.submit(playerOne, 3, 300, 300, 1, players));
        assertEquals(
                LockstepDigestTracker.Result.REJECTED,
                tracker.submit(playerOne, 3, 300, 300, 1, players));
        assertEquals(
                LockstepDigestTracker.Result.REJECTED,
                tracker.submit(playerTwo, 3, 300, 903, 1, players));
    }
}
