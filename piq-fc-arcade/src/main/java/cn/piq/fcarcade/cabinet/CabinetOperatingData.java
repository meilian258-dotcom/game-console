// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Only current content/generation metadata lives in world NBT. Native files use atomic disk storage. */
final class CabinetOperatingData extends SavedData {
    private record Entry(String content,UUID generation){}
    record Selection(String key,String content,UUID generation,UUID previous){}
    private final Map<String,Entry> entries=new HashMap<>();
    private static final int MAX=512;
    static CabinetOperatingData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(new Factory<>(CabinetOperatingData::new,CabinetOperatingData::load),"piq_cabinet_operating_v1");}
    Selection prepare(CabinetTarget target,CabinetGameManifest manifest){
        String key=CabinetSharedGameData.key(target,manifest.backend());var old=entries.get(key);
        if(old==null&&entries.size()>=MAX)throw new IllegalStateException("街机运营设置数量已达上限");
        return new Selection(key,manifest.contentId(),old!=null&&old.content.equals(manifest.contentId())?old.generation:UUID.randomUUID(),old==null?null:old.generation);
    }
    void commit(Selection selection){
        var old=entries.get(selection.key());
        if(old!=null&&old.content.equals(selection.content())&&old.generation.equals(selection.generation()))return;
        if(!Objects.equals(old==null?null:old.generation,selection.previous()))throw new IllegalStateException("运营设置代次已改变");
        if(old==null&&entries.size()>=MAX)throw new IllegalStateException("街机运营设置数量已达上限");
        entries.put(selection.key(),new Entry(selection.content(),selection.generation()));setDirty();
    }
    boolean current(Selection selection){var old=entries.get(selection.key());return old!=null&&old.content.equals(selection.content())&&old.generation.equals(selection.generation());}
    static void selected(MinecraftServer server,CabinetTarget target,CabinetGameManifest manifest){var data=get(server);data.commit(data.prepare(target,manifest));}
    static CabinetOperatingData load(CompoundTag tag,HolderLookup.Provider lookup){
        var data=new CabinetOperatingData();var list=tag.getList("entries",Tag.TAG_COMPOUND);if(list.size()>MAX)return data;
        for(int i=0;i<list.size();i++)try{var t=list.getCompound(i);String key=t.getString("key"),content=t.getString("content");
            if(key.isBlank()||key.length()>384||key.chars().anyMatch(Character::isISOControl)||!content.matches("[a-f0-9]{64}")||!t.hasUUID("generation"))continue;
            data.entries.putIfAbsent(key,new Entry(content,t.getUUID("generation")));
        }catch(RuntimeException ignored){}return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){var list=new ListTag();entries.forEach((key,e)->{var t=new CompoundTag();t.putString("key",key);t.putString("content",e.content);t.putUUID("generation",e.generation);list.add(t);});tag.put("entries",list);return tag;}
}
