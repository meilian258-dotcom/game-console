package cn.piq.fcarcade.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Packet admission before any library lookup; valid sequential downloads have no cooldown. */
final class RomDownloadBudget {
    static final int GLOBAL_CHUNKS_PER_TICK = 4;
    static final int MAX_DOWNLOADS = 8;
    static final long MAX_OUTSTANDING_BYTES = 64L * 1024 * 1024;
    private final Map<UUID, Integer> misses = new HashMap<>();
    private int lookupTick = Integer.MIN_VALUE;
    private int lookups;

    boolean allowLookup(UUID player, int tick) {
        Integer miss = misses.get(player);
        if (miss != null && tick - miss < 40) return false;
        if (lookupTick != tick) {
            lookupTick = tick;
            lookups = 0;
        }
        return ++lookups <= 32;
    }

    void missing(UUID player, int tick) {
        misses.put(player, tick);
    }

    void forget(UUID player) {
        misses.remove(player);
    }

    static boolean canStart(int active, long outstandingBytes, int requestedBytes) {
        return active < MAX_DOWNLOADS && requestedBytes > 0 && outstandingBytes >= 0
                && requestedBytes <= MAX_OUTSTANDING_BYTES - outstandingBytes;
    }
}
