// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

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
final class NativeCabinetAssemblyData extends SavedData {
    final NativeCabinetAssemblyLedger ledger = new NativeCabinetAssemblyLedger();
    final Set<UUID> changing = new HashSet<>();

    static NativeCabinetAssemblyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                NativeCabinetAssemblyData::new, NativeCabinetAssemblyData::load), "piq_native_arcade_cabinet_assemblies");
    }

    private static NativeCabinetAssemblyData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new NativeCabinetAssemblyData();
        var entries = tag.getList("Assemblies", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            try {
                data.ledger.restore(new NativeCabinetAssemblyLedger.Assembly(entry.getUUID("Id"),
                        entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"),
                        NativeCabinetFootprint.Facing.valueOf(entry.getString("Facing")),
                        entry.getBoolean("Closed"), entry.getInt("Cleared")));
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

            entries.add(entry);
        }
        tag.put("Assemblies", entries);
        return tag;
    }
}

