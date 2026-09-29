package cn.piq.fcarcade.cabinet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Global override for every arcade, including unloaded old cabinets. No block/chunk scan. */
public final class CabinetServerSettings extends SavedData {
    private CabinetServerRules rules=CabinetServerRules.DEFAULT;
    private long revision=1;
    private CabinetServerSettings(){}
    private static CabinetServerSettings data(MinecraftServer server){
        if(server==null||!server.isSameThread())throw new IllegalStateException("Server thread required");
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(CabinetServerSettings::new,CabinetServerSettings::load),"piq_arcade_server_rules");
    }
    public static CabinetServerRules rules(MinecraftServer server){return data(server).rules;}
    public static long revision(MinecraftServer server){return data(server).revision;}
    /** Caller must validate OP, held terminal and the current connection before this CAS. */
    static boolean update(MinecraftServer server,long expected,CabinetServerRules rules){
        var data=data(server);
        long before=data.revision;
        if(!data.replace(expected,rules))return false;
        if(before!=data.revision){
            CabinetRooms.serverRulesChanged(server,rules);
            CabinetServerNetwork.broadcast(server);
        }
        return true;
    }
    boolean replace(long expected,CabinetServerRules next){
        java.util.Objects.requireNonNull(next);
        if(expected!=revision||revision==Long.MAX_VALUE)return false;
        if(!rules.equals(next)){rules=next;revision++;setDirty();}
        return true;
    }
    static CabinetServerSettings load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new CabinetServerSettings();
        int seconds=tag.contains("IdleSeconds",3)?tag.getInt("IdleSeconds"):60;
        int range=tag.contains("Range",3)?tag.getInt("Range"):16;
        data.rules=new CabinetServerRules(tag.getBoolean("ImmediateOnExit"),seconds>=0&&seconds<=3600?seconds:60,CabinetPowerSettings.renderDistance(range));
        data.revision=Math.max(1,tag.getLong("Revision"));return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        tag.putBoolean("ImmediateOnExit",rules.immediateOnExit());tag.putInt("IdleSeconds",rules.idleSeconds());
        tag.putInt("Range",rules.range());tag.putLong("Revision",revision);return tag;
    }
}
