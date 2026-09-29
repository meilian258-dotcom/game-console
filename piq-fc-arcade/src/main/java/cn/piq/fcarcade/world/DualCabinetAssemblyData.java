package cn.piq.fcarcade.world;

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
final class DualCabinetAssemblyData extends SavedData {
    final DualCabinetAssemblyLedger ledger = new DualCabinetAssemblyLedger();
    final Set<UUID> changing = new HashSet<>();

    static DualCabinetAssemblyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                DualCabinetAssemblyData::new, DualCabinetAssemblyData::load), "piq_dual_cabinet_assemblies");
    }

    private static DualCabinetAssemblyData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new DualCabinetAssemblyData();
        var entries = tag.getList("Assemblies", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            try {
                data.ledger.restore(new DualCabinetAssemblyLedger.Assembly(entry.getUUID("Id"),
                        entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"),
                        DualCabinetFootprint.Facing.valueOf(entry.getString("Facing")),
                        entry.getBoolean("Closed"), entry.getInt("Cleared"), entry.getBoolean("Compact")));
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
            entry.putBoolean("Compact", assembly.compact());

            entries.add(entry);
        }
        tag.put("Assemblies", entries);
        return tag;
    }
}
