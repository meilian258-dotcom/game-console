package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HomeTvRemovalGateTest {
    @Test void cancelledAdditionalPartDoesNotCommitLedgerOrRefunds() {
        var ledger = new HomeTvAssemblyLedger(); var id = UUID.randomUUID();
        ledger.restore(new HomeTvAssemblyLedger.Assembly(id, 0, 0, 0, HomeTvFootprint.Facing.NORTH, false, 0));
        var emitted = new AtomicInteger();
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.NORTH, 7,
                cell -> true, cell -> true, cell -> true, cell -> cell.part() != 3,
                () -> true, () -> { if (ledger.close(id, 7, 1, 1, 1)) emitted.incrementAndGet(); });
        assertEquals(HomeTvRemovalGate.Result.DENIED, result);
        assertFalse(ledger.get(id).closed()); assertEquals(0, emitted.get());
    }

    @Test void successfulProxyBreakFiresOtherSevenEventsOnceAndCommitsOnce() {
        var events = new ArrayList<Integer>(); var commits = new AtomicInteger();
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.WEST, 5,
                cell -> true, cell -> true, cell -> true,
                cell -> { events.add(cell.part()); return true; }, () -> true, commits::incrementAndGet);
        assertEquals(HomeTvRemovalGate.Result.REMOVED, result);
        assertEquals(7, events.size()); assertEquals(7, events.stream().distinct().count());
        assertFalse(events.contains(5)); assertEquals(1, commits.get());
    }

    @Test void unloadedAnchorRejectsBeforeEventsOrMutationWithoutLoadingIt() {
        var events = new AtomicInteger(); var commits = new AtomicInteger();
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.EAST, 7,
                cell -> cell.part() != 0, cell -> true, cell -> true,
                cell -> { events.incrementAndGet(); return true; }, () -> true, commits::incrementAndGet);
        assertEquals(HomeTvRemovalGate.Result.UNLOADED, result);
        assertEquals(0, events.get()); assertEquals(0, commits.get());
    }

    @Test void eventReplacingOriginalIdentityStopsCommitAndForeignCellsAreNotTargeted() {
        var events = new ArrayList<Integer>(); var commits = new AtomicInteger();
        var identity = new AtomicInteger(1);
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.SOUTH, 0,
                cell -> true, cell -> true, cell -> cell.part() != 3,
                cell -> { events.add(cell.part()); identity.set(2); return true; },
                () -> identity.get() == 1, commits::incrementAndGet);
        assertEquals(HomeTvRemovalGate.Result.REPLACED, result);
        assertFalse(events.contains(3)); assertEquals(0, commits.get());
    }
}
