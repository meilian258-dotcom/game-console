package cn.piq.fcarcade.home;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Independent wide-LCD ownership and one-drop claims; never shares CRT records. */
final class LargeLcdTvAssemblyLedger {
    record Assembly(UUID id, int x, int y, int z, LargeLcdTvFootprint.Facing facing,
                    boolean closed, int cleared, boolean centered) {
        Assembly(UUID id, int x, int y, int z, LargeLcdTvFootprint.Facing facing, boolean closed, int cleared) {
            this(id, x, y, z, facing, closed, cleared, false);
        }

        boolean owns(int part, int px, int py, int pz) {
            if (part < 0 || part >= LargeLcdTvFootprint.cellCount(centered)) return false;
            var cell = LargeLcdTvFootprint.cell(facing, part, centered);
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

    boolean restore(Assembly assembly) {
        if (assembly == null || assembly.id() == null || assembly.facing() == null || assembly.centered()) return false;
        int mask = completionMask(assembly.centered());
        if ((assembly.cleared() & ~mask) != 0 || (!assembly.closed() && assembly.cleared() != 0)) return false;
        if (assembly.closed() && assembly.cleared() == mask) return true;
        return assemblies.putIfAbsent(assembly.id(), assembly) == null;
    }

    /** Closing consumes the single item entitlement before any world/inventory mutation. */
    boolean close(UUID id, int part, int x, int y, int z) {
        var old = get(id);
        if (old == null || old.closed() || pending(id) || !old.owns(part, x, y, z)) return false;
        assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, old.cleared(), old.centered()));
        return true;
    }

    void acknowledge(UUID id, int part) {
        var old = get(id);
        if (old == null || !old.closed() || part < 0 || part >= LargeLcdTvFootprint.cellCount(old.centered())) return;
        int cleared = old.cleared() | (1 << part);
        if (cleared == completionMask(old.centered())) assemblies.remove(id);
        else assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, cleared, old.centered()));
    }

    private static int completionMask(boolean centered) {
        return (1 << LargeLcdTvFootprint.cellCount(centered)) - 1;
    }
}
