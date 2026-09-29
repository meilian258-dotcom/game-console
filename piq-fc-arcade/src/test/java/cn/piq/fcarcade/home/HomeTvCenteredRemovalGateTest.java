package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HomeTvCenteredRemovalGateTest {
    @Test void eachCenteredClickedCellChecksAllTwelvePermissionsAndElevenOtherEvents() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int clicked = 0; clicked < 12; clicked++) {
            var permissions = new ArrayList<Integer>();
            var events = new ArrayList<Integer>();
            var commits = new AtomicInteger();
            var result = HomeTvRemovalGate.attempt(facing, clicked, true, cell -> true,
                    cell -> { permissions.add(cell.part()); return true; }, cell -> true,
                    cell -> { events.add(cell.part()); return true; }, () -> true, commits::incrementAndGet);
            assertEquals(HomeTvRemovalGate.Result.REMOVED, result);
            assertEquals(12, permissions.size());
            assertEquals(12, permissions.stream().distinct().count());
            assertEquals(11, events.size());
            assertEquals(11, events.stream().distinct().count());
            assertFalse(events.contains(clicked));
            assertEquals(1, commits.get());
        }
    }

    @Test void anyUnloadedCellStopsBeforePermissionEventsOrCommit() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int blocked = 0; blocked < 12; blocked++) {
            int unavailable = blocked;
            var effects = new AtomicInteger();
            var result = HomeTvRemovalGate.attempt(facing, 0, true, cell -> cell.part() != unavailable,
                    cell -> { effects.incrementAndGet(); return true; }, cell -> true,
                    cell -> { effects.incrementAndGet(); return true; }, () -> true, effects::incrementAndGet);
            assertEquals(HomeTvRemovalGate.Result.UNLOADED, result);
            assertEquals(0, effects.get());
        }
    }

    @Test void permissionDeniedInAnyReservedCellStopsEventsAndCommit() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int blocked = 0; blocked < 12; blocked++) {
            int forbidden = blocked;
            var effects = new AtomicInteger();
            var result = HomeTvRemovalGate.attempt(facing, 0, true, cell -> true,
                    cell -> cell.part() != forbidden, cell -> true,
                    cell -> { effects.incrementAndGet(); return true; }, () -> true, effects::incrementAndGet);
            assertEquals(HomeTvRemovalGate.Result.DENIED, result);
            assertEquals(0, effects.get());
        }
    }

    @Test void cancellationOfAnyAdditionalCellPreventsAllWorldMutation() {
        for (var facing : HomeTvFootprint.Facing.values()) for (int blocked = 1; blocked < 12; blocked++) {
            int cancelled = blocked;
            var commits = new AtomicInteger();
            var result = HomeTvRemovalGate.attempt(facing, 0, true, cell -> true, cell -> true, cell -> true,
                    cell -> cell.part() != cancelled, () -> true, commits::incrementAndGet);
            assertEquals(HomeTvRemovalGate.Result.DENIED, result);
            assertEquals(0, commits.get());
        }
    }

    @Test void replacementDuringEventPreventsCommitAndForeignCellsReceiveNoBreakEvent() {
        var same = new AtomicBoolean(true);
        var events = new ArrayList<Integer>();
        var commits = new AtomicInteger();
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.WEST, 0, true,
                cell -> true, cell -> true, cell -> cell.part() != 10,
                cell -> { events.add(cell.part()); same.set(false); return true; }, same::get, commits::incrementAndGet);
        assertEquals(HomeTvRemovalGate.Result.REPLACED, result);
        assertFalse(events.contains(10));
        assertEquals(0, commits.get());
    }

    @Test void cellUnloadedByAnEventPreventsCommitAfterRecheck() {
        var loaded = new AtomicBoolean(true);
        var commits = new AtomicInteger();
        var result = HomeTvRemovalGate.attempt(HomeTvFootprint.Facing.EAST, 11, true,
                cell -> cell.part() != 8 || loaded.get(), cell -> true, cell -> true,
                cell -> { loaded.set(false); return true; }, () -> true, commits::incrementAndGet);
        assertEquals(HomeTvRemovalGate.Result.UNLOADED, result);
        assertEquals(0, commits.get());
    }
}
