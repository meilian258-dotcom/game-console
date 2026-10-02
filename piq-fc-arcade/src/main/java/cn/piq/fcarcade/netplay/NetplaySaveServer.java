// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import static cn.piq.fcarcade.netplay.NetplaySaveNetwork.*;

/** Server-thread authority; clients never choose the save owner, directory or expected content. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class NetplaySaveServer {
    private NetplaySaveServer(){}
    public interface Storage extends NetplaySaveSession.Storage {}
    /** Server-observed result, delivered on the server thread after the IO lease is released.
     * persisted alone may describe an earlier checkpoint; require clean for normal shutdown. */
    public record FinishResult(boolean persisted,boolean clean,String reason) {}
    private record Key(Connection connection,long wire){}
    private static final Map<Key,Binding> BINDINGS=new ConcurrentHashMap<>();
    // Globally bounded, including integrated worlds still releasing an IO lease.
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"PIQ-Netplay-save-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    /** Registration must precede the start grant. Null storage explicitly means no saving. */
    public static void open(MinecraftServer server,long wire,Connection host,UUID ticket,NetplaySaveState.Identity identity,String slot,Callable<Storage> storage){
        open(server,wire,host,ticket,identity,slot,storage,true);
    }
    /** Prepared cores may restore, but cannot overwrite a slot until the server grants READY. */
    public static void openPrepared(MinecraftServer server,long wire,Connection host,UUID ticket,NetplaySaveState.Identity identity,String slot,Callable<Storage> storage){
        open(server,wire,host,ticket,identity,slot,storage,false);
    }
    private static void open(MinecraftServer server,long wire,Connection host,UUID ticket,NetplaySaveState.Identity identity,String slot,Callable<Storage> storage,boolean commitsAllowed){
        if(!server.isSameThread())throw new IllegalStateException("保存授权必须在服务器线程注册");
        var key=new Key(host,wire);
        if(BINDINGS.containsKey(key)||BINDINGS.size()>=16)throw new IllegalStateException("Netplay 保存会话已满或重复");
        if(storage!=null&&busy(slot))throw new IllegalStateException("这个 Netplay 存档仍在保存，请稍后开机");
        var binding=new Binding(server,key,ticket,identity,slot,storage);
        binding.session.commitsAllowed=commitsAllowed;
        BINDINGS.put(key,binding);
    }
    public static boolean activate(MinecraftServer server,long wire,Connection host){
        if(!server.isSameThread())throw new IllegalStateException("保存授权必须在服务器线程确认");
        var b=BINDINGS.get(new Key(host,wire));if(b==null||b.server!=server||b.session.closing)return false;
        b.session.commitsAllowed=true;return true;
    }
    /** Cancel/failed preparation is not retirement: revoke all queued and later writes immediately.
     * Keep the binding/lease until its IO has actually released, so reopening cannot race it. */
    public static boolean abort(MinecraftServer server,long wire,Connection host,String reason){
        if(!server.isSameThread())throw new IllegalStateException("保存撤销必须在服务器线程执行");
        var b=BINDINGS.get(new Key(host,wire));if(b==null||b.server!=server)return false;
        b.session.abort(reason);return true;
    }
    public static boolean busy(MinecraftServer server,String slot){return BINDINGS.values().stream().anyMatch(b->b.server==server&&b.session.factory!=null&&b.slot.equals(slot));}
    public static boolean busy(String slot){return BINDINGS.values().stream().anyMatch(b->b.session.factory!=null&&b.slot.equals(slot));}
    public static boolean busyPrefix(String prefix){return BINDINGS.values().stream().anyMatch(b->b.session.factory!=null&&b.slot.startsWith(prefix));}
    /** Gameplay is revoked separately; only this exact former host may commit a final checkpoint. */
    public static void retire(MinecraftServer server,long wire){for(var b:List.copyOf(BINDINGS.values()))if(b.server==server&&b.key.wire==wire)b.session.retire();}
    /** Register before requesting the final capture. Never treats a missing/failed binding as success. */
    public static boolean awaitFinish(MinecraftServer server,long wire,Connection host,java.util.function.Consumer<FinishResult> callback){
        if(!server.isSameThread())throw new IllegalStateException("保存收尾必须在服务器线程注册");
        Objects.requireNonNull(callback);
        var b=BINDINGS.get(new Key(host,wire));
        if(b==null||b.server!=server||b.finishListener!=null)return false;
        b.finishListener=callback;
        return true;
    }
    public static Callable<Storage> file(Path directory,NetplaySaveState.Identity identity){
        return ()->{var store=new NetplaySaveStore(directory,identity);return new Storage(){
            public byte[] read()throws Exception{return store.read();}public void write(byte[] bytes)throws Exception{store.write(bytes);}public void close()throws Exception{store.close();}
        };};
    }
    public static Path directory(MinecraftServer server,String ownerKey,NetplaySaveState.Identity identity){
        Path world=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        return cn.piq.retro.storage.ConsoleStorage.root(world).resolve("netplay-saves").resolve(NetplaySaveState.hash(ownerKey.getBytes(StandardCharsets.UTF_8)))
                .resolve(identity.profile()).resolve(identity.content());
    }
    static void receive(ServerPlayer player,Message m){
        var b=BINDINGS.get(new Key(player.connection.getConnection(),m.session()));
        if(b==null||b.server!=player.getServer()||player.getServer().getPlayerList().getPlayer(player.getUUID())!=player)return;
        b.session.receive(m);
    }
    private static final class Binding {
        final MinecraftServer server;final Key key;final String slot;final NetplaySaveSession session;
        java.util.function.Consumer<FinishResult> finishListener;
        Binding(MinecraftServer server,Key key,UUID ticket,NetplaySaveState.Identity identity,String slot,Callable<Storage> factory){
            this.server=server;this.key=key;this.slot=slot;
            session=new NetplaySaveSession(key.wire,ticket,identity,factory,IO,server::execute,
                m->send(key.connection,m,true),()->BINDINGS.get(key)==this&&key.connection.isConnected(),System::nanoTime,
                ()->BINDINGS.remove(key,this),result->{
                    var listener=finishListener;finishListener=null;
                    if(listener!=null)try{listener.accept(new FinishResult(result.persisted(),result.clean(),result.reason()));}
                    catch(RuntimeException e){System.getLogger(NetplaySaveServer.class.getName()).log(System.Logger.Level.WARNING,"Device save completion callback failed",e);}
                });
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){for(var b:List.copyOf(BINDINGS.values()))if(b.server==event.getServer())b.session.tick();}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){for(var b:List.copyOf(BINDINGS.values()))if(b.server==event.getServer())b.session.dispose();}
}
