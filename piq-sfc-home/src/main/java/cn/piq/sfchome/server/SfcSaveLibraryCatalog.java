// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.sfchome.server;

import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.fcarcade.netplay.NetplaySaveStore;
import cn.piq.sfchome.core.SfcNetplayProfile;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiFunction;

/** Bounded, read-only inspection of the held content's four possible JNI checkpoints.
 * Construct on the server thread; inspect only on a bounded IO worker. No directory scan,
 * migration, writer lease, metadata rewrite or client-selected path is involved. */
final class SfcSaveLibraryCatalog {
    record Row(String id,String version,String name,String owner,String source,
               long modified,long bytes,String slotKey) {}
    record Listing(List<Row> rows,int unreadable) {
        Listing { rows=List.copyOf(rows); }
        String message(){return "仅列当前游戏的本人／当前卡带 JNI 进度；只读，不支持重命名或删除。私人档、托管 SRAM 和恢复备份不在此目录。"
                +(unreadable==0?"":" 有 "+unreadable+" 份进度无法校验或已变化，原档保留，请稍后刷新。");}
    }
    private record Target(String id,String name,String ownerLabel,String source,String owner,
                          Path path,NetplaySaveState.Identity identity) {}
    private final List<Target> targets;

    SfcSaveLibraryCatalog(UUID player,UUID card,String rom,
                         BiFunction<String,NetplaySaveState.Identity,Path> directories){
        Objects.requireNonNull(player);Objects.requireNonNull(card);Objects.requireNonNull(directories);
        var items=new ArrayList<Target>(4);
        for(int mode:new int[]{1,2}){
            String owner=SfcHomeStartPolicy.saveOwner(mode,player,card);
            for(var variant:List.of(SfcNetplayProfile.current(),SfcNetplayProfile.legacy())){
                boolean legacy=variant==SfcNetplayProfile.legacy();
                var identity=NetplaySaveState.identity(variant.template(),rom,Map.of());
                String prefix=mode==1?"personal-":"card-";
                items.add(new Target(prefix+(legacy?"mesen-legacy":"snes9x"),
                        legacy?"Mesen-S · 旧进度（回滚风险）":"Snes9x · 独立进度",mode==1?"本人":"当前实体卡带",
                        "SFC · JNI Netplay · "+(legacy?"Mesen-S 旧格式":"Snes9x"),owner,
                        Objects.requireNonNull(directories.apply(owner,identity)),identity));
            }
        }
        if(items.stream().map(t->t.path().toAbsolutePath().normalize()).distinct().count()!=4)
            throw new IllegalArgumentException("SFC 保存目录必须按归属和核心隔离");
        targets=List.copyOf(items);
    }

    Listing inspect(){
        var rows=new ArrayList<Row>(4);int unreadable=0;
        for(var target:targets){
            try{
                Path file=target.path().resolve("checkpoint.bin");
                BasicFileAttributes before=null;
                try{before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
                catch(NoSuchFileException absent){/* readOnly still checks redirected existing ancestors. */}
                byte[] bytes=NetplaySaveStore.readOnly(target.path(),target.identity());
                if(bytes==null)continue;
                var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(before==null||!after.isRegularFile()||after.isSymbolicLink()||after.isOther()
                        ||after.size()!=bytes.length||before.size()!=after.size()
                        ||!before.lastModifiedTime().equals(after.lastModifiedTime())
                        ||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("进度在读取期间变化");
                rows.add(new Row(target.id(),NetplaySaveState.hash(bytes),target.name(),target.ownerLabel(),target.source(),
                        Math.max(0,after.lastModifiedTime().toMillis()),bytes.length,
                        target.owner()+"|"+target.identity().profile()+"|"+target.identity().content()));
            }catch(IOException|RuntimeException invalid){unreadable++;}
        }
        return new Listing(rows,unreadable);
    }
}
