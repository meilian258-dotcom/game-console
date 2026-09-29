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
    private record Run(MinecraftServer server,long wire,NetplayRelay<Connection> relay){}
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
        return manifest==null?null:new WatchNetplay.Offer(run.wire(),run.relay(),ResourceLocation.parse(room.backend),manifest.gameHash(),room.target);
    }
    static void open(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room){
        long wire=NetplayNetwork.nextAddonId();var relay=NetplayNetwork.room(wire,(Connection)room.host().connection,room.capacity);
        try{
            var manifest=CabinetSharedGameData.get(server).find(room.target,room.backend);
            var backend=ResourceLocation.parse(room.backend);var profile=PROFILES.get(backend);
            if(manifest==null||profile==null)throw new IllegalStateException("Netplay 附属缺少服务器保存能力声明，请更新配套附属");
            var extra=new TreeMap<String,String>();for(var file:manifest.files().subList(1,manifest.files().size()))extra.put(file.name().toLowerCase(Locale.ROOT),file.sha256());
            var identity=NetplaySaveState.identity(profile.apply(manifest.files().getFirst().name().toLowerCase(Locale.ROOT)),manifest.gameHash(),extra);
            int mode=CabinetSyncSettings.saveMode(server,room.target,backend);
            String owner=mode==1?"arcade-personal|"+room.host().player: "arcade-machine|"+room.target.dimension()+"|"+room.target.identity();
            String slot=owner+"|"+identity.profile()+"|"+identity.content();
            NetplaySaveServer.open(server,wire,(Connection)room.host().connection,relay.grant((Connection)room.host().connection,0).id(),identity,slot,
                mode==0?null:NetplaySaveServer.file(NetplaySaveServer.directory(server,owner,identity),identity));
            RUNS.put(room.id,new Run(server,wire,relay));
        }catch(RuntimeException failure){NetplayNetwork.retire(relay);throw failure;}
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
    static void close(UUID room){var run=RUNS.remove(room);if(run!=null){NetplaySaveServer.retire(run.server(),run.wire());NetplayNetwork.retire(run.relay());}}
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
