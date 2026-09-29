package cn.piq.fcarcade.home;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded per-player anti-repeat/rate gate; persisted item revision protects old packets after eviction/restart. */
public final class CartridgeAssemblyGate {
    private final LinkedHashMap<UUID, Boolean> seen = new LinkedHashMap<>();
    private long lastTick = Long.MIN_VALUE;
    public boolean admit(UUID request, long tick) {
        if (request == null || seen.containsKey(request)) return false;
        seen.put(request, Boolean.TRUE);
        while (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
        if (lastTick != Long.MIN_VALUE && tick - lastTick < 4) return false;
        lastTick = tick;
        return true;
    }
    int remembered() { return seen.size(); }
}
