package cn.piq.fcarcade.home;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Per dimension: only known structures, no world scans or chunk tickets. */
final class SuborAssemblyData extends SavedData {
    final SuborAssemblyLedger ledger = new SuborAssemblyLedger();
    final Set<UUID> changing = new HashSet<>();

    static SuborAssemblyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                SuborAssemblyData::new, SuborAssemblyData::load), "piq_home_subor_assemblies");
    }

    private static SuborAssemblyData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new SuborAssemblyData();
        var entries = tag.getList("Assemblies", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            try {
                data.ledger.restore(new SuborAssemblyLedger.Assembly(entry.getUUID("Id"),
                        entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"),
                        SuborFootprint.Facing.valueOf(entry.getString("Facing")),
                        entry.getBoolean("Compact"), entry.getBoolean("Closed"), entry.getInt("Cleared")));
            } catch (IllegalArgumentException ignored) { /* Invalid ownership cannot authorize drops. */ }
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        for (var assembly : ledger.snapshots()) {
            var entry = new CompoundTag();
            entry.putUUID("Id", assembly.id());
            entry.putInt("X", assembly.x()); entry.putInt("Y", assembly.y()); entry.putInt("Z", assembly.z());
            entry.putString("Facing", assembly.facing().name());
            entry.putBoolean("Compact", assembly.compact());
            entry.putBoolean("Closed", assembly.closed()); entry.putInt("Cleared", assembly.cleared());

            entries.add(entry);
        }
        tag.put("Assemblies", entries);
        return tag;
    }
}
