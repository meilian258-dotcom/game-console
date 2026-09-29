// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Six-cell ownership and one-item entitlement, isolated from existing TV save data. */
final class NativeCabinetAssemblyLedger {
    record Assembly(UUID id, int x, int y, int z, NativeCabinetFootprint.Facing facing, boolean closed, int cleared) {
        boolean owns(int part, int px, int py, int pz) {
            if (part < 0 || part >= 6) return false;
            var cell = NativeCabinetFootprint.cell(facing, part);
            return px == x + cell.x() && py == y + cell.y() && pz == z + cell.z();
        }
    }
    private final Map<UUID, Assembly> assemblies = new HashMap<>();
    private final Set<UUID> pendingPlacement = new HashSet<>();
    Assembly get(UUID id) { return id == null ? null : assemblies.get(id); }
    boolean pending(UUID id) { return pendingPlacement.contains(id); }
    void awaitPlacementEvent(UUID id) { if (get(id) != null) pendingPlacement.add(id); }
    void confirmPlacement(UUID id) { pendingPlacement.remove(id); }
    boolean cancelPlacement(UUID id, int part, int x, int y, int z) {
        var entry = get(id);
        if (entry == null || !entry.owns(part, x, y, z) || !pendingPlacement.remove(id)) return false;
        assemblies.remove(id); return true;
    }
    Collection<Assembly> snapshots() { return List.copyOf(assemblies.values()); }
    boolean restore(Assembly entry) {
        if (entry == null || entry.id() == null || entry.facing() == null || (entry.cleared() & ~63) != 0
                || (!entry.closed() && entry.cleared() != 0)) return false;
        if (entry.closed() && entry.cleared() == 63) return true;
        return assemblies.putIfAbsent(entry.id(), entry) == null;
    }
    boolean close(UUID id, int part, int x, int y, int z) {
        var old = get(id);
        if (old == null || old.closed() || pending(id) || !old.owns(part, x, y, z)) return false;
        assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, old.cleared()));
        return true;
    }
    void acknowledge(UUID id, int part) {
        var old = get(id);
        if (old == null || !old.closed() || part < 0 || part >= 6) return;
        int cleared = old.cleared() | (1 << part);
        if (cleared == 63) assemblies.remove(id);
        else assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, cleared));
    }
}

