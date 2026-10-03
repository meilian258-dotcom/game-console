// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded cover-only transfer; clients cannot enumerate a server's cover store. */
public final class SfcCoverService {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final Map<MinecraftServer,SfcCoverStore> STORES=new WeakHashMap<>();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"piq-sfc-covers");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private SfcCoverService(){}
    public static synchronized SfcCoverStore store(MinecraftServer server){return STORES.computeIfAbsent(server,s->new SfcCoverStore(SfcServerContentPaths.location(s.getServerDirectory(),s.getWorldPath(LevelResource.ROOT),SfcServerContentPaths.Area.COVERS)));}
    private static boolean matches(ItemStack stack,String hash){return SfcCartridgeData.isCartridge(stack)&&hash.equals(SfcCartridgeData.coverSha(stack));}
    private static boolean authorized(ServerPlayer p,SfcHomeNetwork.CoverRequest request){
        if(p.hasDisconnected()||!p.isAlive())return false;
        String hash=request.coverSha();
        for(int i=0;i<p.getInventory().getContainerSize();i++)if(matches(p.getInventory().getItem(i),hash))return true;
        for(ServerPlayer other:p.serverLevel().players())if(p.distanceToSqr(other)<=32*32&&(matches(other.getMainHandItem(),hash)||matches(other.getOffhandItem(),hash)))return true;
        var pos=request.consolePos();var level=p.serverLevel();
        return p.distanceToSqr(pos.getCenter())<=32*32&&level.getWorldBorder().isWithinBounds(pos)&&level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof SfcHomeConsoleBlockEntity be&&matches(be.insertedCartridge(),hash);
    }
    public static void download(ServerPlayer p,SfcHomeNetwork.CoverRequest request){
        MinecraftServer server=p.getServer();State st=STATES.computeIfAbsent(server,k->new State());UUID id=p.getUUID();
        if(st.transfers.containsKey(id)||st.transfers.size()>=4||st.tick-st.last.getOrDefault(id,-100L)<20)return;
        st.last.put(id,st.tick);if(!authorized(p,request))return;
        Transfer transfer=new Transfer(request,st.tick);st.transfers.put(id,transfer);
        try{IO.execute(()->{byte[] bytes=null;try{bytes=store(server).read(request.coverSha());}catch(Exception ignored){}byte[] result=bytes;
            server.execute(()->{if(STATES.get(server)!=st||st.transfers.get(id)!=transfer)return;if(result==null||!authorized(p,request)){st.transfers.remove(id);return;}transfer.bytes=result;});
        });}catch(RejectedExecutionException ex){st.transfers.remove(id);}
    }
    static void tick(MinecraftServer server){State st=STATES.get(server);if(st==null)return;st.tick++;
        st.last.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
        for(var entry:List.copyOf(st.transfers.entrySet())){var id=entry.getKey();Transfer t=entry.getValue();ServerPlayer p=server.getPlayerList().getPlayer(id);
            if(p==null||st.tick-t.started>400||!authorized(p,t.request)){st.transfers.remove(id);continue;}
            if(t.bytes==null)continue;int end=Math.min(t.bytes.length,t.offset+SfcHomeNetwork.CHUNK);
            SfcHomeNetwork.send(p,new SfcHomeNetwork.CoverChunk(t.request.coverSha(),t.bytes.length,t.offset,Arrays.copyOfRange(t.bytes,t.offset,end)));t.offset=end;
            if(end==t.bytes.length)st.transfers.remove(id);
        }
    }
    static void close(MinecraftServer server){STATES.remove(server);synchronized(SfcCoverService.class){STORES.remove(server);}}
    private static final class State{long tick;final Map<UUID,Long>last=new HashMap<>();final Map<UUID,Transfer>transfers=new HashMap<>();}
    private static final class Transfer{final SfcHomeNetwork.CoverRequest request;final long started;byte[]bytes;int offset;Transfer(SfcHomeNetwork.CoverRequest r,long t){request=r;started=t;}}
}
