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
final class HomeTvAssemblyData extends SavedData {
    final HomeTvAssemblyLedger ledger = new HomeTvAssemblyLedger();
    final Set<UUID> changing = new HashSet<>();

    static HomeTvAssemblyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                HomeTvAssemblyData::new, HomeTvAssemblyData::load), "piq_home_tv_assemblies");
    }

    private static HomeTvAssemblyData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new HomeTvAssemblyData();
        var entries = tag.getList("Assemblies", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            try {
                data.ledger.restore(new HomeTvAssemblyLedger.Assembly(entry.getUUID("Id"),
                        entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"),
                        HomeTvFootprint.Facing.valueOf(entry.getString("Facing")),
                        entry.getBoolean("Closed"), entry.getInt("Cleared"), entry.getBoolean("Centered")));
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
            entry.putBoolean("Closed", assembly.closed()); entry.putInt("Cleared", assembly.cleared());
            entry.putBoolean("Centered", assembly.centered());
            entries.add(entry);
        }
        tag.put("Assemblies", entries);
        return tag;
    }
}
