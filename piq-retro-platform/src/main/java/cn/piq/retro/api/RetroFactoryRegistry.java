// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Separate client-owned factory table. Merely registering or looking up a
 * factory never opens a core. Do not construct/register this table on behalf
 * of a dedicated server. Common declarations remain in RetroBackendRegistry;
 * the host verifies both tables and all device/session permissions before use.
 */
public final class RetroFactoryRegistry {
    private final Map<String, RetroEmulatorFactory> factories = new LinkedHashMap<>();

    public synchronized void register(String id, RetroEmulatorFactory factory) {
        RetroBackendRegistry.requireId(id);
        Objects.requireNonNull(factory, "backend factory");
        if (factories.containsKey(id)) throw new IllegalArgumentException("Duplicate retro factory: " + id);
        if (factories.size() >= RetroBackendRegistry.MAX_BACKENDS) {
            throw new IllegalStateException("Too many retro factories");
        }
        factories.put(id, factory);
    }

    public synchronized RetroEmulatorFactory find(String id) { return factories.get(id); }
    public synchronized List<String> ids() { return List.copyOf(factories.keySet()); }
}
