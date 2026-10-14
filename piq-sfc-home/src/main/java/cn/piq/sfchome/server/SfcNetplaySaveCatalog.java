// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.flow.HomeLaunchNetwork;
import cn.piq.fcarcade.netplay.NetplayLegacySlot;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.fcarcade.netplay.NetplayVariant;
import cn.piq.sfchome.core.SfcNetplayProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Two trusted, independent checkpoint identities; never scans or migrates another core's files.
 * Construct on the authority thread from the frozen owner/content. inspect/select are IO-worker only. */
final class SfcNetplaySaveCatalog {
    record Selection(NetplayVariant variant,Path directory,NetplaySaveState.Identity identity,
                     String slot,String version,boolean resume,boolean enabled) {}

    private record Entry(int slot,NetplayVariant variant,Path directory,NetplaySaveState.Identity identity,
                         String key,String name,boolean startNewAllowed) {
        HomeLaunchNetwork.Row row(String version){
            return new HomeLaunchNetwork.Row(slot,version,name,identity.content(),1,0,true,false,startNewAllowed);
        }
        Selection selection(String version,boolean resume,boolean enabled){
            return new Selection(variant,enabled?directory:null,identity,key,version,resume,enabled);
        }
    }

    private final Entry current,legacy;

    SfcNetplaySaveCatalog(String owner,String rom,Function<NetplaySaveState.Identity,Path> directories){
        Objects.requireNonNull(owner);Objects.requireNonNull(directories);
        current=entry(1,SfcNetplayProfile.current(),owner,rom,directories,"Snes9x · 独立进度",true);
        legacy=entry(2,SfcNetplayProfile.legacy(),owner,rom,directories,"Mesen-S · 旧进度（回滚风险）",false);
        if(current.identity().equals(legacy.identity())||current.directory().equals(legacy.directory()))
            throw new IllegalArgumentException("SFC 新旧核心保存身份未隔离");
    }

    private static Entry entry(int slot,NetplayVariant variant,String owner,String rom,
                               Function<NetplaySaveState.Identity,Path> directories,String name,boolean fresh){
        var identity=NetplaySaveState.identity(variant.template(),rom,Map.of());
        return new Entry(slot,variant,Objects.requireNonNull(directories.apply(identity)),identity,
                owner+"|"+identity.profile()+"|"+identity.content(),name,fresh);
    }

    Selection withoutSaving(){return current.selection("",false,false);}

    List<HomeLaunchNetwork.Row> inspect()throws IOException{
        var rows=new ArrayList<HomeLaunchNetwork.Row>(2);
        rows.add(current.row(NetplayLegacySlot.inspect(current.directory(),current.identity())));
        String old=NetplayLegacySlot.inspect(legacy.directory(),legacy.identity());
        if(!old.isEmpty())rows.add(legacy.row(old));
        return List.copyOf(rows);
    }

    String slotKey(int slot){return entry(slot).key();}

    Selection select(HomeLaunchNetwork.Choice choice)throws IOException{
        if(choice==null)throw new IllegalArgumentException("请选择对应核心的进度");
        Entry entry=entry(choice.slot());
        // Reject a forged legacy fresh-start before touching storage; the legacy core is resume-only.
        if(!entry.startNewAllowed()&&!choice.resume())throw new IllegalArgumentException("Mesen-S 仅用于继续原有进度；新进度请选择 Snes9x");
        String version=NetplayLegacySlot.inspect(entry.directory(),entry.identity());
        if(!entry.row(version).accepts(choice))throw new IOException("存档、核心或版本已变化，请重新开机选择");
        return entry.selection(version,choice.resume(),true);
    }

    private Entry entry(int slot){
        return switch(slot){case 1->current;case 2->legacy;default->throw new IllegalArgumentException("未知 SFC 核心进度");};
    }
}
