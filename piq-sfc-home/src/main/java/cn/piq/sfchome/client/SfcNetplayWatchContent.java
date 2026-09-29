package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.client.watch.NetplayWatchContent;
import cn.piq.sfchome.SfcHomeMod;
import cn.piq.sfchome.core.SfcNetplayProfile;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

/** Uses only the exact public watch ROM grant. Never requests a controller or a save slot. */
final class SfcNetplayWatchContent {
    private static final AtomicReference<Download> ACTIVE=new AtomicReference<>();
    static final class Download {
        final String hash;final Connection connection;final CompletableFuture<byte[]> result=new CompletableFuture<>();byte[] bytes;int at;
        Download(String hash,Connection connection){this.hash=hash;this.connection=connection;}
        synchronized void accept(SfcHomeNetwork.RomChunk p){
            if(result.isDone())return;
            if(p.total()<32768||p.total()>SfcClientFiles.MAX_ROM||p.offset()!=at||p.data().length==0
                    ||p.data().length>65536||p.data().length>p.total()-at||bytes!=null&&bytes.length!=p.total()){
                result.completeExceptionally(new IllegalStateException("SFC 旁观游戏分片无效"));return;
            }
            if(bytes==null)bytes=new byte[p.total()];System.arraycopy(p.data(),0,bytes,at,p.data().length);at+=p.data().length;
            if(at==bytes.length){result.complete(bytes);bytes=null;}
        }
        synchronized void cancel(){result.cancel(false);bytes=null;}
    }
    private SfcNetplayWatchContent(){}
    static NetplayWatchContent.Preparation prepare(WatchNetwork.NetplayStart grant,Connection connection){
        if(!grant.backend().equals(SfcHomeMod.CABINET_BACKEND))throw new IllegalArgumentException("SFC observer backend");
        var mc=Minecraft.getInstance();var game=mc.gameDirectory.toPath();var pending=new Download(grant.romHash(),connection);
        return new NetplayWatchContent.Preparation(()->{
            try{
                byte[] bytes=SfcClientFiles.cachedRom(game,pending.hash);
                if(bytes==null){
                    if(pending.result.isDone()||!ACTIVE.compareAndSet(null,pending))throw new IllegalStateException("SFC 旁观下载已取消或繁忙");
                    mc.execute(()->{var c=mc.getConnection();if(ACTIVE.get()==pending&&!pending.result.isDone()&&c!=null&&c.getConnection()==connection)
                        SfcHomeNetwork.requestRom(pending.hash);else pending.cancel();});
                    bytes=pending.result.get(60,TimeUnit.SECONDS);if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    SfcClientFiles.cacheRom(game,pending.hash,bytes);
                }
                if(!SfcClientFiles.hash(bytes).equals(pending.hash))throw new IllegalStateException("SFC 旁观游戏校验失败");
                return new CabinetBackend.NetplayContent(SfcNetplayProfile.profile(),bytes,Map.of());
            }finally{ACTIVE.compareAndSet(pending,null);}
        },()->{pending.cancel();ACTIVE.compareAndSet(pending,null);});
    }
    static void chunk(SfcHomeNetwork.RomChunk packet){
        var d=ACTIVE.get();var c=Minecraft.getInstance().getConnection();
        if(d!=null&&c!=null&&c.getConnection()==d.connection&&d.hash.equals(packet.romSha()))d.accept(packet);
    }
}
