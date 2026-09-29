// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Common-side declarations only: no client factory, installed core or built-in system. */
public final class RetroBackendRegistry {
    public static final int MAX_BACKENDS = 16;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private final Map<String, Descriptor> entries = new LinkedHashMap<>();

    public record Descriptor(String id, String displayName, boolean localOnly) {
        public Descriptor {
            requireId(id);
            if (displayName == null || displayName.isBlank() || displayName.length() > 64
                    || displayName.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid retro backend display name");
            }
        }
    }

    public synchronized void register(String id, String displayName, boolean localOnly) {
        Descriptor descriptor = new Descriptor(id, displayName, localOnly);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate retro backend: " + id);
        if (entries.size() >= MAX_BACKENDS) throw new IllegalStateException("Too many retro backends");
        entries.put(id, descriptor);
    }

    /** Unknown IDs remain unknown; there is never a silent fallback to another core. */
    public synchronized Descriptor find(String id) { return entries.get(id); }

    /** Immutable snapshot in registration order, safe to use after releasing the registry monitor. */
    public synchronized List<Descriptor> entries() { return List.copyOf(entries.values()); }

    static void requireId(String id) {
        Objects.requireNonNull(id, "backend id");
        if (id.length() > 128 || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid retro backend id: " + id);
        }
    }
}
