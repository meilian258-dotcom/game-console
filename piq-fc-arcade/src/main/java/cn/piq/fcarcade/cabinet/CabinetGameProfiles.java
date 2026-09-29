package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;

/** One world-owned record per backend + primary ROM hash. Renaming never invalidates ROM caches. */
public final class CabinetGameProfiles extends SavedData {
    private static final int LIMIT=512;
    private final Map<String,CabinetGameProfile> profiles=new HashMap<>();
    static CabinetGameProfiles get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CabinetGameProfiles::new,CabinetGameProfiles::load),"piq_cabinet_game_profiles_v1");}
    static String key(CabinetGameManifest game){return game.backend()+"/"+game.gameHash();}
    CabinetGameProfile find(CabinetGameManifest game){return game==null?CabinetGameProfile.EMPTY:profiles.getOrDefault(key(game),CabinetGameProfile.EMPTY);}
    boolean update(CabinetGameManifest game,CabinetGameProfile proposed){
        String key=key(game);if(find(game).revision()!=proposed.revision())return false;
        if(!profiles.containsKey(key)&&profiles.size()>=LIMIT)throw new IllegalStateException("游戏资料数量已达上限");
        profiles.put(key,proposed.next());setDirty();return true;
    }
    public static CabinetGameProfile forCabinet(LegacyFcArcadeBlockEntity cabinet){
        if(!(cabinet.getLevel() instanceof ServerLevel level))return CabinetGameProfile.EMPTY;
        if(!CabinetCoinPolicy.supported(cabinet.cabinetBackend().toString()))return CabinetGameProfile.EMPTY;
        var target=new CabinetTarget(level.dimension().location(),cabinet.getBlockPos(),cabinet.cabinetId(),cabinet instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity);
        var primary=CabinetLinks.master(level.getServer(),target);
        return get(level.getServer()).find(CabinetSharedGameData.get(level.getServer()).find(primary,cabinet.cabinetBackend().toString()));
    }
    public static CompoundTag write(CabinetGameProfile p){
        var tag=new CompoundTag();tag.putString("name",p.name());tag.putInt("players",p.players());tag.putInt("orientation",p.orientation().ordinal());tag.putInt("aspect",p.aspect().ordinal());tag.putInt("revision",p.revision());return tag;
    }
    public static CabinetGameProfile read(CompoundTag t){
        int orientation=t.getInt("orientation"),aspect=t.getInt("aspect");
        if(orientation<0||orientation>=CabinetGameProfile.Orientation.values().length||aspect<0||aspect>=CabinetGameProfile.Aspect.values().length)throw new IllegalArgumentException("Unknown game profile enum");
        return new CabinetGameProfile(t.getString("name"),t.getInt("players"),CabinetGameProfile.Orientation.values()[orientation],CabinetGameProfile.Aspect.values()[aspect],t.getInt("revision"));
    }
    static CabinetGameProfiles load(CompoundTag root,HolderLookup.Provider unused){
        var result=new CabinetGameProfiles();var list=root.getList("profiles",Tag.TAG_COMPOUND);
        if(list.size()>LIMIT)return result;
        for(int i=0;i<list.size();i++)try{
            var t=list.getCompound(i);String key=t.getString("key");
            if(key.length()>193||!key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+/[0-9a-f]{64}"))continue;
            result.profiles.putIfAbsent(key,read(t));
        }catch(IllegalArgumentException ignored){/* One invalid profile cannot break game data. */}
        return result;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider unused){
        var list=new ListTag();profiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var t=write(e.getValue());t.putString("key",e.getKey());list.add(t);});root.put("profiles",list);return root;
    }
}
