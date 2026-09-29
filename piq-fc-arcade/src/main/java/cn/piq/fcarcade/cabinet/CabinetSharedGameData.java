package cn.piq.fcarcade.cabinet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-owned manifests only, no client paths or per-player saves. */
final class CabinetSharedGameData extends SavedData {
    private final Map<String,CabinetGameManifest> bindings=new HashMap<>();
    private static final int MAX_BINDINGS=512;
    static CabinetSharedGameData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CabinetSharedGameData::new,CabinetSharedGameData::load),"piq_cabinet_games_v1");}
    static String key(CabinetTarget target,String backend){return target.dimension()+"/"+target.identity()+"/"+backend;}
    CabinetGameManifest find(CabinetTarget target,String backend){return bindings.get(key(target,backend));}
    List<CabinetGameManifest> catalog(String backend){return bindings.values().stream().filter(m->m.backend().equals(backend)).distinct().sorted(Comparator.comparing(CabinetGameManifest::contentId)).toList();}
    void put(CabinetTarget target,CabinetGameManifest manifest){String key=key(target,manifest.backend());if(!bindings.containsKey(key)&&bindings.size()>=MAX_BINDINGS)throw new IllegalStateException("机柜游戏绑定数量已达上限");bindings.put(key,manifest);setDirty();}
    private static CabinetSharedGameData load(CompoundTag root,HolderLookup.Provider lookup){
        var data=new CabinetSharedGameData();ListTag list=root.getList("bindings",Tag.TAG_COMPOUND);
        if(list.size()>MAX_BINDINGS)return data;
        for(int i=0;i<list.size();i++)try{CompoundTag t=list.getCompound(i);String key=t.getString("key");
            if(key.length()>384||key.isBlank()||key.chars().anyMatch(Character::isISOControl))continue;
            CabinetGameManifest m=readManifest(t);if(data.bindings.putIfAbsent(key,m)!=null)throw new IllegalArgumentException("Duplicate binding");
        }catch(RuntimeException ignored){/* Invalid individual data never grants file/lease access. */}
        return data;
    }
    static CabinetGameManifest readManifest(CompoundTag t){
        ListTag list=t.getList("files",Tag.TAG_COMPOUND);if(list.isEmpty()||list.size()>CabinetGameManifest.MAX_FILES)throw new IllegalArgumentException("Invalid files");
        List<CabinetGameManifest.Entry> files=new ArrayList<>();for(int i=0;i<list.size();i++){var f=list.getCompound(i);files.add(new CabinetGameManifest.Entry(f.getString("name"),f.getString("hash"),f.getInt("size")));}return new CabinetGameManifest(t.getString("backend"),files);
    }
    static void writeManifest(CompoundTag t,CabinetGameManifest m){t.putString("backend",m.backend());ListTag files=new ListTag();for(var e:m.files()){CompoundTag f=new CompoundTag();f.putString("name",e.name());f.putString("hash",e.sha256());f.putInt("size",e.size());files.add(f);}t.put("files",files);}
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider lookup){ListTag list=new ListTag();bindings.forEach((key,m)->{CompoundTag t=new CompoundTag();t.putString("key",key);writeManifest(t,m);list.add(t);});root.put("bindings",list);return root;}
}
