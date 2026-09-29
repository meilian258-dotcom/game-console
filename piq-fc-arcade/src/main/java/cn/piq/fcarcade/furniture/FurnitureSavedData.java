package cn.piq.fcarcade.furniture;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

final class FurnitureSavedData extends SavedData {
    final FurnitureLedger ledger=new FurnitureLedger();
    static FurnitureSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(FurnitureSavedData::new,FurnitureSavedData::load),"piq_furniture_benches");
    }
    private static FurnitureSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new FurnitureSavedData();
        for(Tag raw:tag.getList("Benches",Tag.TAG_COMPOUND)) {
            var t=(CompoundTag)raw;
            try { if(t.hasUUID("Id"))data.ledger.add(new FurnitureLedger.Entry(t.getUUID("Id"),t.getInt("X"),t.getInt("Y"),t.getInt("Z"),t.getInt("Turns"),t.getString("Wood"),t.getBoolean("Closed"),t.getInt("Absent"))); }
            catch(IllegalArgumentException ignored) { /* Malformed data has no item entitlement. */ }
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        var list=new ListTag();
        for(var e:ledger.entries()) {
            var t=new CompoundTag();t.putUUID("Id",e.id());t.putInt("X",e.x());t.putInt("Y",e.y());t.putInt("Z",e.z());
            t.putInt("Turns",e.turns());t.putString("Wood",e.wood());t.putBoolean("Closed",e.closed());t.putInt("Absent",e.absent());list.add(t);
        }
        tag.put("Benches",list);return tag;
    }
}
