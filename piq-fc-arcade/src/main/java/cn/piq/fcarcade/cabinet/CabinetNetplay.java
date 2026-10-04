package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Optional addon lane. Existing cabinet topology, grants and physical leases remain authoritative. */
public final class CabinetNetplay {
    private CabinetNetplay(){}
    private static final Map<ResourceLocation,Integer> SUPPORTED=new HashMap<>();
    private static final Map<ResourceLocation,java.util.function.Function<String,NetplayProfile>> PROFILES=new HashMap<>();
    private record Run(MinecraftServer server,long wire,NetplayRelay<Connection> relay,Connection host,
            java.util.function.Function<String,NetplayProfile> profile,int saveMode,String owner,
            CabinetNetplayContentBinding content){}
    private static final Map<UUID,Run> RUNS=new HashMap<>();
    public static synchronized void register(ResourceLocation backend){register(backend,2);}
    public static synchronized void register(ResourceLocation backend,int ports){if(ports<1||ports>4)throw new IllegalArgumentException("Ports");SUPPORTED.put(Objects.requireNonNull(backend),ports);}
    public static synchronized void register(ResourceLocation backend,int ports,java.util.function.Function<String,NetplayProfile> profile){register(backend,ports);PROFILES.put(backend,Objects.requireNonNull(profile));}
    public static synchronized boolean supported(ResourceLocation backend){return SUPPORTED.containsKey(backend);}
    public static synchronized int maxPlayers(ResourceLocation backend){return SUPPORTED.getOrDefault(backend,0);}
    static boolean active(UUID room){return RUNS.containsKey(room);}
    static WatchNetplay.Offer observation(MinecraftServer server,UUID source){
        var run=RUNS.get(source);var room=CabinetRooms.syncRoom(server,source);
        if(run==null||run.server()!=server||room==null||!room.ready||run.relay().closed())return null;
        var manifest=CabinetSharedGameData.get(server).find(room.target,room.backend);
        return !run.content().matches(manifest)?null:new WatchNetplay.Offer(run.wire(),run.relay(),ResourceLocation.parse(room.backend),manifest.gameHash(),room.target);
    }
    static void open(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room){
        if(!server.isSameThread()||RUNS.containsKey(room.id))throw new IllegalStateException("Netplay 房间重复或线程无效");
        long wire=NetplayNetwork.nextAddonId();var relay=NetplayNetwork.room(wire,(Connection)room.host().connection,room.capacity);
        try{
            var backend=ResourceLocation.parse(room.backend);var profile=PROFILES.get(backend);
            if(profile==null)throw new IllegalStateException("Netplay 附属缺少服务器保存能力声明，请更新配套附属");
            int mode=CabinetSyncSettings.saveMode(server,room.target,backend);
            String owner=mode==1?"arcade-personal|"+room.host().player: "arcade-machine|"+room.target.dimension()+"|"+room.target.identity();
            Connection host=(Connection)room.host().connection;
            // The host may upload/select another ROM or add BIOS after the start grant.
            // Register saves only from the verified END manifest, before its success reply.
            RUNS.put(room.id,new Run(server,wire,relay,host,profile,mode,owner,
                    new CabinetNetplayContentBinding(host,room.host().player,room.host().id,room.backend)));
        }catch(RuntimeException failure){NetplayNetwork.retire(relay);throw failure;}
    }
    /** Called on the server thread after END verification, before publishing the manifest/grant
     * and before replying success. Run the returned action if that publication fails: it aborts
     * only this exact run, never a replacement room. Non-Netplay/guest/watch calls cannot bind saves. */
    static Runnable contentReady(ServerPlayer player,UUID lease,ResourceLocation backend,CabinetTarget target,CabinetGameManifest manifest){
        MinecraftServer server=player.getServer();
        if(server==null||!server.isSameThread())throw new IllegalStateException("Netplay 内容授权必须在服务器线程确认");
        Objects.requireNonNull(manifest);
        for(var entry:RUNS.entrySet()){
            Run run=entry.getValue();if(run.server()!=server)continue;
            var room=CabinetRooms.syncRoom(server,entry.getKey());
            if(room==null||!room.target.equals(target)||!room.backend.equals(backend.toString()))continue;
            var host=room.host();if(host==null)throw new IllegalStateException("Netplay 主持已离开");
            var member=CabinetRooms.authorized(player,room.id,lease);
            if(!host.id.equals(lease)){
                // Peers and read-only observers only consume the already-bound manifest.
                if(!run.content().matches(manifest))throw new IllegalStateException("Netplay 主持内容尚未就绪或已改变");
                return ()->{};
            }
            if(member!=host||run.relay().closed()||!run.content().owns(player.connection.getConnection(),player.getUUID(),lease,backend.toString()))
                throw new IllegalStateException("Netplay 主持内容授权已失效");
            run.content().bind(player.connection.getConnection(),player.getUUID(),lease,backend.toString(),room.ready,manifest,()->{
                var extra=new TreeMap<String,String>();
                for(var file:manifest.files().subList(1,manifest.files().size()))extra.put(file.name().toLowerCase(Locale.ROOT),file.sha256());
                var identity=NetplaySaveState.identity(run.profile().apply(manifest.files().getFirst().name().toLowerCase(Locale.ROOT)),manifest.gameHash(),extra);
                String slot=run.owner()+"|"+identity.profile()+"|"+identity.content();
                var ticket=run.relay().grant(run.host(),0);
                if(ticket==null)throw new IllegalStateException("Netplay 主持授权已结束");
                NetplaySaveServer.open(server,run.wire(),run.host(),ticket.id(),identity,slot,
                        run.saveMode()==0?null:NetplaySaveServer.file(NetplaySaveServer.directory(server,run.owner(),identity),identity));
            });
            UUID roomId=room.id;
            return ()->abortContentCommit(roomId,run);
        }
        return ()->{};
    }
    private static void abortContentCommit(UUID roomId,Run expected){
        if(RUNS.get(roomId)!=expected)return;
        expected.content().retire();
        String reason="Netplay 内容提交失败，未启动本局，请重新开机";
        NetplaySaveServer.abort(expected.server(),expected.wire(),expected.host(),reason);
        try{
            var room=CabinetRooms.syncRoom(expected.server(),roomId);
            if(room!=null)CabinetRooms.syncClose(expected.server(),room.host(),reason);
        }finally{if(RUNS.get(roomId)==expected)close(roomId);}
    }
    static boolean assignment(ServerPlayer p,CabinetRoomNetwork.Assignment a){
        var run=RUNS.get(a.room());if(run==null)return false;
        if(run.server()!=p.getServer()||CabinetRooms.authorized(p,a.room(),a.member())==null)throw new IllegalStateException("Invalid cabinet Netplay lease");
        var member=CabinetRooms.authorized(p,a.room(),a.member());
        if(member.port!=a.port())throw new IllegalStateException("Netplay seat mismatch");
        var ticket=run.relay().grant(p.connection.getConnection(),member.port);
        if(ticket==null)throw new IllegalStateException("Netplay room full");
        NetplayNetwork.authorize(run.wire(),p.connection.getConnection(),run.relay());
        PacketDistributor.sendToPlayer(p,new CabinetRoomNetwork.NetplayStart(a,run.wire(),ticket.id()));return true;
    }
    static void removed(UUID room,Object connection,boolean host){var run=RUNS.get(room);if(run==null)return;if(host)close(room);else run.relay().revoke((Connection)connection);}
    static void close(UUID room){var run=RUNS.remove(room);if(run!=null){run.content().retire();NetplaySaveServer.retire(run.server(),run.wire());NetplayNetwork.retire(run.relay());}}
    static void stopped(MinecraftServer server){for(var e:List.copyOf(RUNS.entrySet()))if(e.getValue().server()==server)close(e.getKey());}
    static void tick(MinecraftServer server){
        for(var e:List.copyOf(RUNS.entrySet())){
            var run=e.getValue();if(run.server()!=server)continue;var room=CabinetRooms.syncRoom(server,e.getKey());
            if(room==null){close(e.getKey());continue;}
            if(run.relay().closed()){CabinetRooms.syncClose(server,room.host(),"Netplay 主持连接已结束");continue;}
            Set<Connection> current=new HashSet<>();
            current.addAll(WatchNetplay.connections(server,e.getKey()));
            for(var member:room.members)if(member!=null){var p=server.getPlayerList().getPlayer(member.player);if(p!=null&&CabinetRooms.authorized(p,room.id,member.id)==member)current.add(p.connection.getConnection());}
            run.relay().renew(current);NetplayNetwork.prune(run.relay(),current);
        }
    }
}
