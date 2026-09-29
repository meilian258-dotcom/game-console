package cn.piq.fcarcade.cabinet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server-thread event-driven deadlines, independent of player positions and input frequency. */
final class CabinetIdleShutdown<K> {
    static final long IDLE_NANOS = CabinetPowerSettings.DEFAULT_SECONDS * 1_000_000_000L;
    private final Map<K, Long> deadlines = new HashMap<>();
    private long nextDeadline;

    void update(K room, boolean hasController, long now) {
        update(room,hasController,true,CabinetPowerSettings.DEFAULT_SECONDS,now);
    }

    void update(K room,boolean hasController,boolean enabled,int seconds,long now) {
        if (hasController || !enabled || seconds==0) { cancel(room); return; }
        if (deadlines.containsKey(room)) return; // Repeated idle notifications do not extend the timer.
        long deadline = now + CabinetPowerSettings.seconds(seconds) * 1_000_000_000L;
        if (deadlines.isEmpty() || deadline - nextDeadline < 0) nextDeadline = deadline;
        deadlines.put(room, deadline);
    }

    void cancel(K room) {
        if (deadlines.remove(room) != null) recomputeNext();
    }

    List<K> pollDue(long now) {
        // The normal tick path is only a cached timestamp comparison, not a room/player scan.
        if (deadlines.isEmpty() || now - nextDeadline < 0) return List.of();
        var due = new ArrayList<K>();
        var iterator = deadlines.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() >= 0) { due.add(entry.getKey()); iterator.remove(); }
        }
        recomputeNext();
        return List.copyOf(due);
    }

    private void recomputeNext() {
        boolean first = true;
        for (long deadline : deadlines.values()) {
            if (first || deadline - nextDeadline < 0) nextDeadline = deadline;
            first = false;
        }
    }
}
