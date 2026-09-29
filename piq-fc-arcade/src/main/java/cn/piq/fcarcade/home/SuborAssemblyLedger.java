package cn.piq.fcarcade.home;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned Subor ownership and one-item entitlement, isolated from existing TV save data. */
final class SuborAssemblyLedger {
    record Assembly(UUID id, int x, int y, int z, SuborFootprint.Facing facing, boolean compact, boolean closed, int cleared) {
        /** Missing size in a legacy caller or save must continue to mean four cells. */
        Assembly(UUID id, int x, int y, int z, SuborFootprint.Facing facing, boolean closed, int cleared) {
            this(id, x, y, z, facing, false, closed, cleared);
        }
        int completeMask() { return (1 << SuborFootprint.partCount(compact)) - 1; }
        boolean owns(int part, int px, int py, int pz) {
            if (part < 0 || part >= SuborFootprint.partCount(compact)) return false;
            var cell = SuborFootprint.cell(facing, part, compact);
            return px == x + cell.x() && py == y && pz == z + cell.z();
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
        if (entry == null || entry.id() == null || entry.facing() == null || (entry.cleared() & ~entry.completeMask()) != 0
                || (!entry.closed() && entry.cleared() != 0)) return false;
        if (entry.closed() && entry.cleared() == entry.completeMask()) return true;
        return assemblies.putIfAbsent(entry.id(), entry) == null;
    }
    boolean close(UUID id, int part, int x, int y, int z) {
        var old = get(id);
        if (old == null || old.closed() || pending(id) || !old.owns(part, x, y, z)) return false;
        assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), old.compact(), true, old.cleared()));
        return true;
    }
    void acknowledge(UUID id, int part) {
        var old = get(id);
        if (old == null || !old.closed() || part < 0 || part >= SuborFootprint.partCount(old.compact())) return;
        int cleared = old.cleared() | (1 << part);
        if (cleared == old.completeMask()) assemblies.remove(id);
        else assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), old.compact(), true, cleared));
    }
}
