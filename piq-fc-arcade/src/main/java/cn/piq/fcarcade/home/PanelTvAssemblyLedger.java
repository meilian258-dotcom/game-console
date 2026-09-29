package cn.piq.fcarcade.home;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Independent 2x2/3x2 panel ownership and one-drop claims; never shares old TV records. */
final class PanelTvAssemblyLedger {
    record Assembly(UUID id, int x, int y, int z, PanelTvFootprint.Facing facing,
                    boolean closed, int cleared, boolean wide, boolean wall) {
        Assembly(UUID id, int x, int y, int z, PanelTvFootprint.Facing facing, boolean closed, int cleared, boolean wide) {
            this(id, x, y, z, facing, closed, cleared, wide, false);
        }
        Assembly(UUID id, int x, int y, int z, PanelTvFootprint.Facing facing, boolean closed, int cleared) {
            this(id, x, y, z, facing, closed, cleared, false);
        }

        boolean owns(int part, int px, int py, int pz) {
            if (part < 0 || part >= PanelTvFootprint.cellCount(wide)) return false;
            var cell = PanelTvFootprint.cell(facing, part, wide);
            return px == x + cell.x() && py == y + cell.y() && pz == z + cell.z();
        }
    }

    private final Map<UUID, Assembly> assemblies = new HashMap<>();
    private final Set<UUID> pendingPlacement = new HashSet<>();

    Assembly get(UUID id) { return id == null ? null : assemblies.get(id); }
    boolean pending(UUID id) { return pendingPlacement.contains(id); }
    void awaitPlacementEvent(UUID id) { if (get(id) != null) pendingPlacement.add(id); }
    void confirmPlacement(UUID id) { pendingPlacement.remove(id); }
    /** Only the creating transaction may call this before returning success to vanilla. */
    void abortPlacement(UUID id) {
        pendingPlacement.remove(id);var old=get(id);if(old==null)return;
        assemblies.put(id,new Assembly(id,old.x(),old.y(),old.z(),old.facing(),true,old.cleared(),old.wide(),old.wall()));
    }
    boolean cancelPlacement(UUID id, int part, int x, int y, int z) {
        var entry = get(id);
        if (entry == null || !entry.owns(part, x, y, z) || !pendingPlacement.remove(id)) return false;
        assemblies.remove(id); return true;
    }
    Collection<Assembly> snapshots() { return List.copyOf(assemblies.values()); }

    boolean restore(Assembly assembly) {
        if (assembly == null || assembly.id() == null || assembly.facing() == null) return false;
        int mask = completionMask(assembly.wide());
        if ((assembly.cleared() & ~mask) != 0 || (!assembly.closed() && assembly.cleared() != 0)) return false;
        if (assembly.closed() && assembly.cleared() == mask) return true;
        return assemblies.putIfAbsent(assembly.id(), assembly) == null;
    }

    /** Closing consumes the single item entitlement before any world/inventory mutation. */
    boolean close(UUID id, int part, int x, int y, int z) {
        var old = get(id);
        if (old == null || old.closed() || pending(id) || !old.owns(part, x, y, z)) return false;
        assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, old.cleared(), old.wide(),old.wall()));
        return true;
    }

    void acknowledge(UUID id, int part) {
        var old = get(id);
        if (old == null || !old.closed() || part < 0 || part >= PanelTvFootprint.cellCount(old.wide())) return;
        int cleared = old.cleared() | (1 << part);
        if (cleared == completionMask(old.wide())) assemblies.remove(id);
        else assemblies.put(id, new Assembly(id, old.x(), old.y(), old.z(), old.facing(), true, cleared, old.wide(),old.wall()));
    }

    private static int completionMask(boolean wide) {
        return (1 << PanelTvFootprint.cellCount(wide)) - 1;
    }
}
