package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SuborRemovalGateTest {
    @Test void cancelledAdditionalPartDoesNotCommitLedgerOrRefunds() {
        var ledger = new SuborAssemblyLedger(); var id = UUID.randomUUID();
        ledger.restore(new SuborAssemblyLedger.Assembly(id, 0, 0, 0, SuborFootprint.Facing.NORTH, false, 0));
        var emitted = new AtomicInteger();
        var result = SuborRemovalGate.attempt(SuborFootprint.Facing.NORTH, 3,
                cell -> true, cell -> true, cell -> true, cell -> cell.part() != 2,
                () -> true, () -> { if (ledger.close(id, 3, 1, 0, 1)) emitted.incrementAndGet(); });
        assertEquals(SuborRemovalGate.Result.DENIED, result);
        assertFalse(ledger.get(id).closed()); assertEquals(0, emitted.get());
    }

    @Test void successfulProxyBreakFiresOtherThreeEventsOnceAndCommitsOnce() {
        var events = new ArrayList<Integer>(); var commits = new AtomicInteger();
        var result = SuborRemovalGate.attempt(SuborFootprint.Facing.WEST, 1,
                cell -> true, cell -> true, cell -> true,
                cell -> { events.add(cell.part()); return true; }, () -> true, commits::incrementAndGet);
        assertEquals(SuborRemovalGate.Result.REMOVED, result);
        assertEquals(3, events.size()); assertEquals(3, events.stream().distinct().count());
        assertFalse(events.contains(1)); assertEquals(1, commits.get());
    }

    @Test void unloadedAnchorRejectsBeforeEventsOrMutationWithoutLoadingIt() {
        var events = new AtomicInteger(); var commits = new AtomicInteger();
        var result = SuborRemovalGate.attempt(SuborFootprint.Facing.EAST, 3,
                cell -> cell.part() != 0, cell -> true, cell -> true,
                cell -> { events.incrementAndGet(); return true; }, () -> true, commits::incrementAndGet);
        assertEquals(SuborRemovalGate.Result.UNLOADED, result);
        assertEquals(0, events.get()); assertEquals(0, commits.get());
    }

    @Test void eventReplacingOriginalIdentityStopsCommitAndForeignCellsAreNotTargeted() {
        var events = new ArrayList<Integer>(); var commits = new AtomicInteger();
        var identity = new AtomicInteger(1);
        var result = SuborRemovalGate.attempt(SuborFootprint.Facing.SOUTH, 0,
                cell -> true, cell -> true, cell -> cell.part() != 2,
                cell -> { events.add(cell.part()); identity.set(2); return true; },
                () -> identity.get() == 1, commits::incrementAndGet);
        assertEquals(SuborRemovalGate.Result.REPLACED, result);
        assertFalse(events.contains(2)); assertEquals(0, commits.get());
    }

    @Test void compactChecksOnlyItsTwoCellsAndFiresOnlyTheOtherBreakEvent() {
        for (var facing : SuborFootprint.Facing.values()) {
            var checked = new ArrayList<Integer>(); var events = new ArrayList<Integer>();
            var commits = new AtomicInteger();
            var result = SuborRemovalGate.attempt(facing, true, 1,
                    cell -> { checked.add(cell.part()); return cell.part() < 2; },
                    cell -> cell.part() < 2, cell -> true,
                    cell -> { events.add(cell.part()); return true; }, () -> true, commits::incrementAndGet);
            assertEquals(SuborRemovalGate.Result.REMOVED, result);
            assertEquals(java.util.List.of(0, 1, 0, 1), checked);
            assertEquals(java.util.List.of(0), events); assertEquals(1, commits.get());
        }
    }

    @Test void compactDeniedCellOrInvalidRearPartCannotCommit() {
        var commits = new AtomicInteger();
        var denied = SuborRemovalGate.attempt(SuborFootprint.Facing.NORTH, true, 0,
                cell -> true, cell -> cell.part() != 1, cell -> true, cell -> true,
                () -> true, commits::incrementAndGet);
        assertEquals(SuborRemovalGate.Result.DENIED, denied);
        var invalid = SuborRemovalGate.attempt(SuborFootprint.Facing.NORTH, true, 2,
                cell -> { fail("Invalid compact part must be rejected before world checks"); return true; },
                cell -> true, cell -> true, cell -> true, () -> true, commits::incrementAndGet);
        assertEquals(SuborRemovalGate.Result.REPLACED, invalid); assertEquals(0, commits.get());
    }
}
