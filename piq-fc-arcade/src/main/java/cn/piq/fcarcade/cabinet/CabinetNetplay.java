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
    private static final Map<ResourceLocation,Map<String,NetplayVariant>> VARIANTS=new HashMap<>();
    private static final Map<ResourceLocation,String> DEFAULT_VARIANTS=new HashMap<>();
    private record Run(MinecraftServer server,long wire,NetplayRelay<Connection> relay,Connection host,
            java.util.function.Function<String,NetplayProfile> profile,int saveMode,String owner,
            CabinetNetplayContentBinding content,NetplayLaunchSpec launch,long operatingWire){}
    private record Operating(CabinetOperatingData.Selection selection,NetplaySaveState.Identity identity){}
    private static final Map<UUID,Operating> OPERATING=new HashMap<>();
    private static final Map<UUID,Run> RUNS=new HashMap<>();
    public static synchronized void register(ResourceLocation backend){register(backend,2);}
    public static synchronized void register(ResourceLocation backend,int ports){if(ports<1||ports>4)throw new IllegalArgumentException("Ports");SUPPORTED.put(Objects.requireNonNull(backend),ports);}
    public static synchronized void register(ResourceLocation backend,int ports,java.util.function.Function<String,NetplayProfile> profile){register(backend,ports);PROFILES.put(backend,Objects.requireNonNull(profile));}
    public static synchronized void registerVariants(ResourceLocation backend,String defaultVariant,List<NetplayVariant> variants){
        if(variants.isEmpty()||variants.size()>4)throw new IllegalArgumentException("Netplay variants");
        var catalog=new LinkedHashMap<String,NetplayVariant>();
        for(var variant:variants)if(catalog.putIfAbsent(variant.id(),variant)!=null)throw new IllegalArgumentException("Duplicate Netplay variant");
        var selected=catalog.get(defaultVariant);if(selected==null)throw new IllegalArgumentException("Missing default Netplay variant");
        register(backend,variants.stream().mapToInt(v->v.template().ports()).max().orElseThrow(),selected::profile);
        VARIANTS.put(backend,Collections.unmodifiableMap(catalog));DEFAULT_VARIANTS.put(backend,defaultVariant);
    }
    public static synchronized List<NetplayVariant> variants(ResourceLocation backend){return List.copyOf(VARIANTS.getOrDefault(backend,Map.of()).values());}
    public static synchronized String defaultVariant(ResourceLocation backend){return DEFAULT_VARIANTS.getOrDefault(backend,NetplayLaunchSpec.LEGACY);}
    public static synchronized NetplayVariant variant(ResourceLocation backend,String id){return VARIANTS.getOrDefault(backend,Map.of()).get(id);}
    /** Both controller and watch clients check the same local trusted declaration before starting. */
    public static synchronized void verify(ResourceLocation backend,NetplayLaunchSpec grant,NetplayProfile actual){
        var catalog=VARIANTS.get(backend);
        if(catalog==null){if(!NetplayLaunchSpec.UNSPECIFIED.equals(grant))throw new IllegalArgumentException("未安装此 Netplay 后端变体");return;}
        var variant=catalog.get(grant.variant());
        if(variant==null)throw new IllegalArgumentException("未安装本局选择的 Netplay 后端，请更新配套附属；不会自动切换");
        variant.verify(grant,actual);
    }
    public static synchronized boolean supported(ResourceLocation backend){return SUPPORTED.containsKey(backend);}
    public static synchronized int maxPlayers(ResourceLocation backend){return SUPPORTED.getOrDefault(backend,0);}
    static boolean active(UUID room){return RUNS.containsKey(room);}
    static WatchNetplay.Offer observation(MinecraftServer server,UUID source){
        var run=RUNS.get(source);var room=CabinetRooms.syncRoom(server,source);
        if(run==null||run.server()!=server||room==null||!room.ready||run.relay().closed())return null;
        var manifest=CabinetSharedGameData.get(server).find(room.target,room.backend);
        return !run.content().matches(manifest)?null:new WatchNetplay.Offer(run.wire(),run.relay(),ResourceLocation.parse(room.backend),manifest.gameHash(),room.target,run.launch());
    }
    static void open(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room){
        if(!server.isSameThread()||RUNS.containsKey(room.id))throw new IllegalStateException("Netplay 房间重复或线程无效");
        long wire=NetplayNetwork.nextAddonId();var relay=NetplayNetwork.room(wire,(Connection)room.host().connection,room.capacity);
        try{
            var backend=ResourceLocation.parse(room.backend);var profile=PROFILES.get(backend);
            if(profile==null)throw new IllegalStateException("Netplay 附属缺少服务器保存能力声明，请更新配套附属");
            String choice=CabinetSyncSettings.netplayVariant(server,room.target,backend);
            var selected=variant(backend,choice);var launch=NetplayLaunchSpec.UNSPECIFIED;
            if(VARIANTS.containsKey(backend)){
                if(selected==null)throw new IllegalStateException("本机柜 Netplay 后端已不可用，请明确重新选择；不会自动切换");
                if(room.capacity>selected.template().ports())throw new IllegalStateException("本局席位超过所选 Netplay 后端上限");
                profile=selected::profile;launch=selected.launch();
            }else if(!choice.equals(NetplayLaunchSpec.LEGACY))throw new IllegalStateException("未注册的 Netplay 后端变体");
            int mode=CabinetSyncSettings.saveMode(server,room.target,backend);
            if(!launch.persistenceSupported())mode=0; // Explicit experimental selection never reads/writes old saves.
            String owner=mode==1?"arcade-personal|"+room.host().player: "arcade-machine|"+room.target.dimension()+"|"+room.target.identity();
            Connection host=(Connection)room.host().connection;
            // The host may upload/select another ROM or add BIOS after the start grant.
            // Register saves only from the verified END manifest, before its success reply.
            RUNS.put(room.id,new Run(server,wire,relay,host,profile,mode,owner,
                    new CabinetNetplayContentBinding(host,room.host().player,room.host().id,room.backend),launch,
                    PgmServicePolicy.supportsBackend(backend.toString())&&CabinetOperatingFiles.supported(profile.apply("contract.zip"))?NetplayNetwork.nextAddonId():0));
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
                if(!run.launch().persistenceSupported())return;
                var extra=new TreeMap<String,String>();
                for(var file:manifest.files().subList(1,manifest.files().size()))extra.put(file.name().toLowerCase(Locale.ROOT),file.sha256());
                var identity=NetplaySaveState.identity(run.profile().apply(manifest.files().getFirst().name().toLowerCase(Locale.ROOT)),manifest.gameHash(),extra);
                String slot=run.owner()+"|"+identity.profile()+"|"+identity.content();
                var ticket=run.relay().grant(run.host(),0);
                if(ticket==null)throw new IllegalStateException("Netplay 主持授权已结束");
                NetplaySaveServer.open(server,run.wire(),run.host(),ticket.id(),identity,slot,
                        run.saveMode()==0?null:NetplaySaveServer.file(NetplaySaveServer.directory(server,run.owner(),identity),identity));
                try{if(run.operatingWire()!=0){
                    var selection=CabinetOperatingData.get(server).prepare(room.target,manifest);
                    var operatingIdentity=CabinetOperatingFiles.identity(identity);
                    String operatingOwner="arcade-operating|"+selection.key()+"|"+selection.generation();
                    var storage=NetplaySaveServer.file(NetplaySaveServer.directory(server,operatingOwner,operatingIdentity),operatingIdentity);
                    NetplaySaveServer.openPrepared(server,run.operatingWire(),run.host(),ticket.id(),operatingIdentity,operatingOwner,()->{
                        var delegate=storage.call();return new NetplaySaveServer.Storage(){
                            public byte[] read()throws Exception{byte[] bytes=delegate.read();CabinetOperatingFiles.unpack(operatingIdentity,bytes);return bytes;}
                            public void write(byte[] bytes)throws Exception{
                                CabinetOperatingFiles.unpack(operatingIdentity,bytes);
                                requireOperatingCommit(run,host.player,room.target,manifest,selection);
                                delegate.write(bytes);
                            }
                            public void close()throws Exception{delegate.close();}
                        };
                    });
                    OPERATING.put(entry.getKey(),new Operating(selection,operatingIdentity));
                }}catch(RuntimeException failure){
                    OPERATING.remove(entry.getKey());
                    NetplaySaveServer.abort(server,run.wire(),run.host(),"街机运营配置准备失败，未启动本局");
                    if(run.operatingWire()!=0)NetplaySaveServer.abort(server,run.operatingWire(),run.host(),"街机运营配置准备失败，未启动本局");
                    throw failure;
                }
            });
            UUID roomId=room.id;
            return ()->abortContentCommit(roomId,run);
        }
        return ()->{};
    }
    /** After successful manifest publication; rejected/cancelled preparation never replaces the active generation. */
    static void contentPublished(MinecraftServer server,CabinetTarget target,CabinetGameManifest manifest){
        // Metadata is scoped to the trusted arcade adapter, never home consoles/other addons.
        if(!PgmServicePolicy.supportsBackend(manifest.backend()))return;
        for(var e:RUNS.entrySet()){
            var run=e.getValue();var room=CabinetRooms.syncRoom(server,e.getKey());var operating=OPERATING.get(e.getKey());
            if(run.server()==server&&room!=null&&room.target.equals(target)&&run.content().matches(manifest)&&operating!=null){CabinetOperatingData.get(server).commit(operating.selection());return;}
        }
        CabinetOperatingData.selected(server,target,manifest);
    }
    /** Storage workers ask the server thread to recheck authority at final commit, including
     * after room retirement. A late previous generation can never affect the new selection. */
    private static void requireOperatingCommit(Run run,UUID playerId,CabinetTarget target,CabinetGameManifest manifest,
            CabinetOperatingData.Selection selection)throws Exception {
        var permitted=new java.util.concurrent.CompletableFuture<Boolean>();
        run.server().execute(()->{try{
            var player=run.server().getPlayerList().getPlayer(playerId);
            var level=run.server().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,target.dimension()));
            permitted.complete(player!=null&&player.connection.getConnection()==run.host()&&player.hasPermissions(2)
                    &&target.matches(level)&&manifest.equals(CabinetSharedGameData.get(run.server()).find(target,manifest.backend()))
                    &&CabinetOperatingData.get(run.server()).current(selection));
        }catch(RuntimeException failure){permitted.completeExceptionally(failure);}});
        if(!permitted.get(15,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("街机运营设置授权或游戏已改变；保留此前已确认设置");
    }
    /** Invoked only after the existing OP + active 1P + room/content/lease checks. */
    static void authorizeOperating(ServerPlayer player,UUID roomId){
        var run=RUNS.get(roomId);if(run==null||run.server()!=player.getServer()||!player.hasPermissions(2)
                ||run.host()!=player.connection.getConnection()||!OPERATING.containsKey(roomId))return;
        NetplaySaveServer.activate(run.server(),run.operatingWire(),run.host());
    }
    private static void abortContentCommit(UUID roomId,Run expected){
        if(RUNS.get(roomId)!=expected)return;
        expected.content().retire();
        String reason="Netplay 内容提交失败，未启动本局，请重新开机";
        NetplaySaveServer.abort(expected.server(),expected.wire(),expected.host(),reason);
        if(expected.operatingWire()!=0)NetplaySaveServer.abort(expected.server(),expected.operatingWire(),expected.host(),reason);
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
        PacketDistributor.sendToPlayer(p,new CabinetRoomNetwork.NetplayStart(a,run.wire(),ticket.id(),run.launch(),member.port==0?run.operatingWire():0));return true;
    }
    static void removed(UUID room,Object connection,boolean host){var run=RUNS.get(room);if(run==null)return;if(host)close(room);else run.relay().revoke((Connection)connection);}
    static void close(UUID room){OPERATING.remove(room);var run=RUNS.remove(room);if(run!=null){run.content().retire();NetplaySaveServer.retire(run.server(),run.wire());if(run.operatingWire()!=0)NetplaySaveServer.retire(run.server(),run.operatingWire());NetplayNetwork.retire(run.relay());}}
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
