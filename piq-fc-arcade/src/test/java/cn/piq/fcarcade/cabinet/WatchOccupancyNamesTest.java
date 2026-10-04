package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchOccupancyNamesTest {
    @Test void onlyResolvedOperatorsAppearOnceInSeatOrder() {
        var first = UUID.randomUUID(); var second = UUID.randomUUID(); var offline = UUID.randomUUID();
        assertEquals("Second、First", WatchOccupancyNames.format(List.of(second, offline, first, second),
                id -> id.equals(first) ? "First" : id.equals(second) ? "Second" : null));
    }
    @Test void emptySeatsDoNotInventHostOrObserver() {
        assertEquals("", WatchOccupancyNames.format(List.of(), id -> "Host"));
        assertEquals("", WatchOccupancyNames.format(null, id -> "Host"));
    }
    @Test void limitsProviderWorkAndNameLength() {
        var ids = java.util.stream.IntStream.range(0, 8).mapToObj(i -> new UUID(0,i)).toList();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var text = WatchOccupancyNames.format(ids, id -> { calls.incrementAndGet(); return "x".repeat(100); });
        assertEquals(4, calls.get()); assertEquals(4 * 32 + 3, text.length());
    }
}
