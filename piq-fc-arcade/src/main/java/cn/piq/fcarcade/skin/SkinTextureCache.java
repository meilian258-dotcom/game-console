package cn.piq.fcarcade.skin;

import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Bounded access-ordered ownership of native textures; eviction releases the native resource. */
public final class SkinTextureCache<V> {
    private final int capacity;
    private final Consumer<V> release;
    private final LinkedHashMap<String, V> entries = new LinkedHashMap<>(16, 0.75f, true);

    public SkinTextureCache(int capacity, Consumer<V> release) {
        if (capacity < 1) throw new IllegalArgumentException("Texture capacity must be positive");
        this.capacity = capacity;
        this.release = release;
    }
    public V get(String key) { return entries.get(key); }
    public int size() { return entries.size(); }

    /** Call before allocating a new image, not after allocating a ninth texture. */
    public String evictIfFull() {
        if (entries.size() < capacity) return null;
        var oldest = entries.entrySet().iterator();
        var entry = oldest.next();
        String key = entry.getKey();
        V value = entry.getValue();
        oldest.remove();
        release.accept(value);
        return key;
    }

    public void put(String key, V value) {
        V replaced = entries.remove(key);
        if (replaced != null) release.accept(replaced);
        evictIfFull();
        entries.put(key, value);
    }

    public void clear() {
        RuntimeException failure = null;
        for (V value : entries.values()) {
            try { release.accept(value); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        entries.clear();
        if (failure != null) throw failure;
    }
}
