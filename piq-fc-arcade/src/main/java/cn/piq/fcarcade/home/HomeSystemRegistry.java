package cn.piq.fcarcade.home;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Setup-only registry; pure so duplicate/freeze behavior can be tested without Minecraft. */
final class HomeSystemRegistry<K, V> {
    private final K reserved;
    private final Map<K, V> entries = new HashMap<>();
    private boolean locked;
    HomeSystemRegistry(K reserved) { this.reserved = Objects.requireNonNull(reserved); }
    synchronized void register(K id, V hooks) {
        Objects.requireNonNull(id); Objects.requireNonNull(hooks);
        if (locked) throw new IllegalStateException("Home system registration is locked");
        if (reserved.equals(id) || entries.containsKey(id))
            throw new IllegalArgumentException("Home system ID is reserved or already registered: " + id);
        entries.put(id, hooks);
    }
    synchronized void lock() { locked = true; }
    synchronized V get(K id) { return entries.get(id); }
}
