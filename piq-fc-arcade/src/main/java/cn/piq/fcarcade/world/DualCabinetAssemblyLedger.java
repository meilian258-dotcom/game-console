package cn.piq.fcarcade.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned cabinet ownership and one-item entitlement, isolated from TV save data. */
final class DualCabinetAssemblyLedger {
    record Assembly(UUID id, int x, int y, int z, DualCabinetFootprint.Facing facing, boolean closed, int cleared, boolean compact) {
        Assembly(UUID id, int x, int y, int z, DualCabinetFootprint.Facing facing, boolean closed, int cleared) {
            this(id, x, y, z, facing, closed, cleared, false);
        }
        int count() { return DualCabinetFootprint.count(compact); }
        int mask() { return (1 << count()) - 1; }
        List<DualCabinetFootprint.Cell> cells() { return DualCabinetFootprint.cells(facing, compact); }
        boolean owns(int part, int px, int py, int pz) {
            if (part < 0 || part >= count()) return false;
            var cell = DualCabinetFootprint.cell(facing, part, compact);
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
        if (entry == null || entry.id() == null || entry.facing() == null || (entry.cleared() & ~entry.mask()) != 0
                || (!entry.closed() && entry.cleared() != 0)) return false;
        if (entry.closed() && entry.cleared() == entry.mask()) return true;
        return assemblies.putIfAbsent(entry.id(), entry) == null;
    }
    boolean close(UUID id, int part, int x, int y, int z) {
        var old = get(id);
        if (old == null || old.closed() || pending(id) || !old.owns(part, x, y, z)) return false;
        assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, old.cleared(), old.compact()));
        return true;
    }
    void acknowledge(UUID id, int part) {
        var old = get(id);
        if (old == null || !old.closed() || part < 0 || part >= old.count()) return;
        int cleared = old.cleared() | (1 << part);
        if (cleared == old.mask()) assemblies.remove(id);
        else assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, cleared, old.compact()));
    }
}
