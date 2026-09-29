package cn.piq.sfchome.server;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.netplay.*;
import cn.piq.sfchome.data.*;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcJoinNetwork;
import cn.piq.sfchome.net.SfcRepairNetwork;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server owns leases. Player media drives only the host core; local sync drives every core. */
public final class SfcHomeServer {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private record NetplayRun(long wire,NetplayRelay<net.minecraft.network.Connection> room){}
    private static final Map<Session,NetplayRun> NETPLAY=new java.util.concurrent.ConcurrentHashMap<>();
    private static void retireNetplay(Session s){var run=NETPLAY.remove(s);if(run!=null){NetplaySaveServer.retire(s.connection.level().getServer(),run.wire());NetplayNetwork.retire(run.room());}}
    private static void renewNetplay(Session s){
        var run=NETPLAY.get(s);if(run==null)return;
        var allowed=new HashSet<net.minecraft.network.Connection>();allowed.add(s.host.connection);
        var server=s.connection.level().getServer();
        allowed.addAll(cn.piq.fcarcade.cabinet.WatchNetplay.connections(server,s.watchSource));
        for(var l:s.ports)if(l!=null){var p=server.getPlayerList().getPlayer(l.player);if(p!=null&&endpointFacts(p,l,s.connection))allowed.add(l.connection);}
        if(s.join!=null&&s.join.second!=null){var l=s.join.second;var p=server.getPlayerList().getPlayer(l.player);if(p!=null&&endpointFacts(p,l,s.connection))allowed.add(l.connection);}
        run.room().renew(allowed);NetplayNetwork.prune(run.room(),allowed);
    }
    private static final ThreadLocal<Boolean> INTERACTING=ThreadLocal.withInitial(()->false);
    private static boolean registered;
    private SfcHomeServer(){}
    private static State state(MinecraftServer s){return STATES.computeIfAbsent(s,k->new State());}
    private static boolean hostedMode(SfcHomeConsoleBlockEntity c){return c.synchronizationMode()==cn.piq.fcarcade.cabinet.CabinetSyncMode.SERVER_MEDIA;}
    private static String hostedUnavailable(ServerLevel level,ExternalHomeConsoleBlockEntity c){
        if(!cn.piq.fcarcade.cabinet.CabinetHostingConfig.enabled())return "服务端托管未启用；需服主在 piq-sync-server.toml 开启 serverHosting。";
        var factory=cn.piq.fcarcade.server.hosted.ServerCoreRegistry.find(c.systemId());if(factory==null)return "SFC 服务端核心未注册。";
        var server=level.getServer();var world=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        try{return factory.unavailableReason(new cn.piq.fcarcade.server.hosted.ServerCoreContext(server.getServerDirectory(),cn.piq.retro.storage.ConsoleStorage.root(world).resolve("piq-sfc-home/hosted-saves"),c.hardwareId(),c.hardwareId()));}
        catch(RuntimeException|LinkageError unavailable){return "SFC 服务端核心能力检查失败。";}
    }
    private static boolean modeSupported(SfcHomeConsoleBlockEntity c){return c.synchronizationMode()==cn.piq.fcarcade.cabinet.CabinetSyncMode.MEDIA&&cn.piq.fcarcade.cabinet.CabinetHostingConfig.playerAllowed()||c.synchronizationMode()==cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC&&cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed()||hostedMode(c)&&hostedUnavailable((ServerLevel)c.getLevel(),c)==null;}
    private static SfcHostedWorker openHosted(Session s,AutoCloseable capacity){
        var server=s.connection.level().getServer();var world=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        var context=new cn.piq.fcarcade.server.hosted.ServerCoreContext(server.getServerDirectory(),cn.piq.retro.storage.ConsoleStorage.root(world).resolve("piq-sfc-home/hosted-saves"),s.host.player,s.connection.consoleId());
        return new SfcHostedWorker(s.watchSource,s.host.id,context,cn.piq.retro.storage.ConsoleStorage.root(world).resolve("piq-sfc-home/roms"),s.rom,capacity);
    }
    static boolean watchHosted(MinecraftServer server,cn.piq.fcarcade.cabinet.WatchSource source){
        if(server==null||!server.isSameThread()||source==null)return false;State st=STATES.get(server);if(st==null)return false;
        for(Session s:st.sessions.values())if(s.hosted!=null&&s.watchSource.equals(source.descriptor().source())&&s.host.id.equals(source.descriptor().hostLease()))return true;return false;
    }
    /** Server-thread-only snapshots for read-only spectators; no ROM or input handles escape. */
    static java.util.List<cn.piq.fcarcade.cabinet.WatchSource> watchSources(MinecraftServer server){
        if(server==null||!server.isSameThread())return List.of();
        State state=STATES.get(server);if(state==null)return List.of();
        var result=new ArrayList<cn.piq.fcarcade.cabinet.WatchSource>();
        for(Session session:state.sessions.values()){
            if(session.hostReady==null||session.clock==null)continue;
            ServerPlayer host=server.getPlayerList().getPlayer(session.host.player);
            if(!hostValid(host,session))continue;
            var c=session.connection;
            var descriptor=new cn.piq.fcarcade.cabinet.WatchDescriptor(c.systemId(),session.watchSource,session.host.id,
                    c.level().dimension().location(),new cn.piq.fcarcade.cabinet.WatchAnchor(c.console().getBlockPos(),c.consoleId()),
                    c.linkId(),List.of(new cn.piq.fcarcade.cabinet.WatchAnchor(c.television().getBlockPos(),c.televisionId())));
            result.add(new cn.piq.fcarcade.cabinet.WatchSource(descriptor,session.host.player));
        }
        return List.copyOf(result);
    }
    static boolean watchParticipant(MinecraftServer server,UUID player){
        if(server==null||player==null||!server.isSameThread())return false;
        State state=STATES.get(server);if(state==null)return false;
        for(Session session:state.sessions.values()){
            if(session.host.player.equals(player)||session.join!=null&&session.join.gate.applicant.equals(player))return true;
            for(Lease lease:session.ports)if(lease!=null&&lease.player.equals(player))return true;
        }
        return false;
    }
    static cn.piq.fcarcade.cabinet.WatchNetplay.Offer netplayWatch(MinecraftServer server,cn.piq.fcarcade.cabinet.WatchSource source){
        State st=STATES.get(server);Session s=st==null?null:st.sessions.get(source.descriptor().origin().identity());
        var run=s==null?null:NETPLAY.get(s);
        return run!=null&&s.clock!=null&&!run.room().closed()&&watchSources(server).contains(source)
                ?new cn.piq.fcarcade.cabinet.WatchNetplay.Offer(run.wire(),run.room(),cn.piq.sfchome.SfcHomeMod.CABINET_BACKEND,s.rom,null):null;
    }
    record LocalWatchSource(cn.piq.fcarcade.cabinet.WatchSource source,long session,int epoch,String rom,
                           String initial,double fps,int frame,int checkpoint,String checkpointSha) {}
    /** Server-thread-only immutable identity: no controller lease is granted by this view. */
    static LocalWatchSource localWatchSource(MinecraftServer server,UUID sourceId){
        State st=STATES.get(server);if(st==null||!server.isSameThread())return null;
        for(var source:watchSources(server))if(source.descriptor().source().equals(sourceId)){
            Session s=st.sessions.get(source.descriptor().origin().identity());
            if(s==null||NETPLAY.containsKey(s)||s.mode!=cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC||s.hostReady==null||s.clock==null)return null;
            var checkpoint=s.repairs.latest(s.host.player);
            return new LocalWatchSource(source,s.id,s.epoch,s.rom,s.hostReady.initialStateHash(),s.hostReady.targetFps(),s.frame,
                    checkpoint==null?0:checkpoint.frame(),checkpoint==null?s.hostReady.initialStateHash():checkpoint.expected());
        }return null;
    }
    static SfcRepairLedger.Replay localWatchReplay(MinecraftServer server,LocalWatchSource view,int first){
        State st=STATES.get(server);Session s=st==null?null:st.sessions.get(view.source().descriptor().origin().identity());
        if(s==null||s.id!=view.session()||s.epoch!=view.epoch()||!s.watchSource.equals(view.source().descriptor().source())
                ||s.mode!=cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC)throw new IllegalStateException("Observer source changed");
        return s.repairs.replay(first);
    }
    static String localWatchDigest(MinecraftServer server,LocalWatchSource view,int frame){
        State st=STATES.get(server);Session s=st==null?null:st.sessions.get(view.source().descriptor().origin().identity());
        return s!=null&&s.id==view.session()&&s.epoch==view.epoch()?s.repairs.hostDigest(frame):null;
    }
    /** Pure membership snapshot: a watch source is not a controller capability. */
    private static Session playerMediaSession(MinecraftServer server,cn.piq.fcarcade.cabinet.WatchSource source){
        if(server==null||!server.isSameThread()||source==null)return null;
        State st=STATES.get(server);if(st==null||!watchSources(server).contains(source))return null;
        for(Session s:st.sessions.values())if(s.playerMedia()&&s.hosted==null&&s.hostReady!=null&&s.clock!=null
                &&s.watchSource.equals(source.descriptor().source())&&s.host.id.equals(source.descriptor().hostLease())
                &&s.host.player.equals(source.hostPlayer()))return s;
        return null;
    }
    private static List<ServerPlayer> playerMediaRecipients(MinecraftServer server,Session s){
        var targets=new ArrayList<ServerPlayer>(2);
        for(int port=0;port<2;port++){
            Lease lease=s.ports[port];if(lease==null||s.ready[port]==null||lease.player.equals(s.host.player))continue;
            ServerPlayer player=server.getPlayerList().getPlayer(lease.player);
            if(player!=null&&endpointFacts(player,lease,s.connection))targets.add(player);
        }
        return List.copyOf(targets);
    }
    static int watchControlRecipients(MinecraftServer server,cn.piq.fcarcade.cabinet.WatchSource source){
        Session s=playerMediaSession(server,source);return s==null?0:playerMediaRecipients(server,s).size();
    }
    /** Called only by the authenticated, bounded, complete-frame watch ingress. */
    static void relayPlayerMedia(MinecraftServer server,cn.piq.fcarcade.cabinet.WatchSource source,List<cn.piq.fcarcade.cabinet.CabinetMediaPacket> batch){
        Session s=playerMediaSession(server,source);if(s==null||batch==null||batch.isEmpty())return;
        // Recheck generation even though the shared ingress already validates all parts.
        for(var part:batch)if(part==null||!s.watchSource.equals(part.room())||!s.host.id.equals(part.hostMember()))return;
        for(ServerPlayer target:playerMediaRecipients(server,s)){
            int port=port(s,target.getUUID());if(port<0)continue;
            Lease lease=s.ports[port];
            if(lease!=null&&s.ready[port]!=null&&endpointFacts(target,lease,s.connection))
                cn.piq.sfchome.net.SfcHostedNetwork.send(target.connection.getConnection(),s.id,s.epoch,lease.id,batch);
        }
    }
    public static void register(){if(registered)return;registered=true;
        HomeSystems.register(SfcHomeConsoleBlockEntity.SYSTEM_ID,new HomeSystems.ServerHooks(){
            @Override public void onInteract(ServerPlayer p,InteractionHand hand,HomeSystems.Connection c,BlockHitResult hit){} // Legacy TV/body entry never powers on or grants a controller.
            @Override public boolean onPowerOn(ServerPlayer p,HomeSystems.Connection c){return powerOn(p,c);}
            @Override public void onPowerOff(ServerLevel l,ExternalHomeConsoleBlockEntity c){if(c instanceof SfcHomeConsoleBlockEntity console)stop(state(l.getServer()),console.hardwareId(),"主机已关机；手柄仍保留");}
            @Override public void onReset(ServerPlayer p,HomeSystems.Connection c){reset(p,c);}
            @Override public void onController(ServerPlayer p,HomeSystems.Connection c,int port){if(c.console() instanceof SfcHomeConsoleBlockEntity console)claim(p,console,state(p.getServer()),port);}
            @Override public void onControllerDock(ServerPlayer p,ExternalHomeConsoleBlockEntity c,int port){if(c instanceof SfcHomeConsoleBlockEntity console)claim(p,console,state(p.getServer()),port);}
            @Override public boolean isRunning(ServerLevel l,ExternalHomeConsoleBlockEntity c){var s=state(l.getServer()).sessions.get(c.hardwareId());return s!=null&&s.connection.console()==c;}
            @Override public boolean synchronizationSettingsAvailable(){return true;}
            @Override public int synchronizationSupportedModes(ServerLevel l,ExternalHomeConsoleBlockEntity c){return (cn.piq.fcarcade.cabinet.CabinetHostingConfig.playerAllowed()?1:0)|(cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed()?10:0)|(hostedUnavailable(l,c)==null?4:0);}
            @Override public String synchronizationUnavailableReason(ServerLevel l,ExternalHomeConsoleBlockEntity c,cn.piq.fcarcade.cabinet.CabinetSyncMode mode){return mode==cn.piq.fcarcade.cabinet.CabinetSyncMode.SERVER_MEDIA?Objects.requireNonNullElse(hostedUnavailable(l,c),"服务端托管可用。"):mode==cn.piq.fcarcade.cabinet.CabinetSyncMode.MEDIA&&cn.piq.fcarcade.cabinet.CabinetHostingConfig.playerAllowed()?"玩家音画串流可用；主持退出会安全停机，不自动转交进度。":HomeSyncPolicy.unavailable(mode);}
            @Override public boolean synchronizationSettingsBusy(ServerLevel l,ExternalHomeConsoleBlockEntity c){var st=state(l.getServer());return st.sessions.containsKey(c.hardwareId())||st.stopping.containsKey(c.hardwareId());}
            @Override public void onPlaybackStopped(ServerLevel l,ExternalHomeConsoleBlockEntity c,HomeSystems.StopReason reason){if(c instanceof SfcHomeConsoleBlockEntity console)stop(state(l.getServer()),console.hardwareId(),"电视连接已停止；手柄仍保留");}
            @Override public void onRemoved(ServerLevel l,ExternalHomeConsoleBlockEntity c){if(c instanceof SfcHomeConsoleBlockEntity console){releaseConsole(l.getServer(),console,"主机已拆除");ItemStack card=console.eject();if(!card.isEmpty())Block.popResource(l,c.getBlockPos(),card);}}
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e)->tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(SfcHomeServer::tossedController);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)->{var st=STATES.remove(e.getServer());if(st!=null)for(var s:st.sessions.values()){retireNetplay(s);if(s.hosted!=null)s.hosted.close();}SfcCartridgeEditorService.closeServer(e.getServer());SfcLocalWatchServer.close(e.getServer());});
    }
    public static void feedback(ServerPlayer p,String text){p.displayClientMessage(Component.literal("[SFC] "+text),true);}
    private static boolean basic(ServerPlayer p,SfcHomeConsoleBlockEntity c){
        if(!p.getServer().isSameThread()||p.getServer().getPlayerList().getPlayer(p.getUUID())!=p||!p.connection.getConnection().isConnected()||!p.isAlive()||p.isSpectator()||p.hasDisconnected()||c.getLevel()!=p.serverLevel()||c.isRemoved()
                ||!p.serverLevel().hasChunkAt(c.getBlockPos())||p.serverLevel().getBlockEntity(c.getBlockPos())!=c||!p.serverLevel().mayInteract(p,c.getBlockPos()))return false;
        double distance=p.distanceToSqr(c.getBlockPos().getCenter());
        var connection=HomeSystems.connection(p.serverLevel(),c.getBlockPos());
        if(connection.isPresent()&&connection.get().console()==c)distance=Math.min(distance,p.distanceToSqr(connection.get().television().getBlockPos().getCenter()));
        return distance<=64;
    }
    public static void interactDirect(ServerPlayer p,InteractionHand hand,BlockPos pos,BlockHitResult hit){
        if(INTERACTING.get()||!hit.getBlockPos().equals(pos)||!p.serverLevel().hasChunkAt(pos)||!(p.serverLevel().getBlockEntity(pos) instanceof SfcHomeConsoleBlockEntity console))return;
        ItemStack held=p.getItemInHand(hand);INTERACTING.set(true);
        try{if(!basic(p,console))return;var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,hand,pos,hit));if(e.isCanceled()||e.getUseBlock()==TriState.FALSE||e.getUseItem()==TriState.FALSE||p.getItemInHand(hand)!=held||!basic(p,console))return;if(SfcCartridgeData.isCartridge(held)){interact(p,hand,console);return;}if(HomeApplianceService.tryButton(p,pos,hand,hit)!=InteractionResult.PASS)return;if(SfcControllerData.isController(held)||p.isShiftKeyDown())interact(p,hand,console);else HomeApplianceService.interactConsole(p,pos,hit);}catch(RuntimeException error){feedback(p,"本次交互已取消");}finally{INTERACTING.remove();}
    }
    private static void interact(ServerPlayer p,InteractionHand h,SfcHomeConsoleBlockEntity c){
        if(!basic(p,c))return;ItemStack held=p.getItemInHand(h);State st=state(p.getServer());
        if(!st.interactions.allow(p.getUUID(),st.tick))return;
        if(SfcCartridgeData.isCartridge(held)){
            if(p.isShiftKeyDown())return;
            if(!SfcCartridgeData.supported(held)){feedback(p,"卡带包含不支持的附加数据");return;}
            if(c.hasCartridge()){feedback(p,"先空手潜行右键取出原卡带");return;}
            if(c.insert(held)){held.shrink(1);p.getInventory().setChanged();HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.CARTRIDGE_INSERT);feedback(p,"卡带已插入；按主机电源开机");}return;
        }
        if(SfcControllerData.isController(held)){Lease lease=st.leases.get(SfcControllerData.leaseId(held));if(lease==null||lease.console!=c||!validLease(p,lease,true)||!authorizedController(p,lease)||!validLease(p,lease,true)){feedback(p,"此手柄未绑定当前主机或交互权限失效");return;}returnController(p.getServer(),lease);return;}
        if(!held.isEmpty()||!p.getOffhandItem().isEmpty())return;
        if(p.isShiftKeyDown()){
            if(!c.hasCartridge()){feedback(p,"没有插入卡带");return;}
            stop(st,c.hardwareId(),"卡带已取出");ItemStack card=c.eject();p.setItemInHand(h,card);if(!card.isEmpty())HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.CARTRIDGE_EJECT);feedback(p,"卡带已取出；请先保存退出以免丢失进度");return;
        }
        feedback(p,"请瞄准电源、重置键或桌上的手柄操作");
    }
    private static void claim(ServerPlayer p,SfcHomeConsoleBlockEntity c,State st,int requestedPort){
        if(requestedPort<0||requestedPort>1||!st.interactions.allow(p.getUUID(),st.tick))return;
        Session s=st.sessions.get(c.hardwareId());
        if(s!=null&&NETPLAY.containsKey(s)&&requestedPort!=(s.host.player.equals(p.getUUID())?0:1)){feedback(p,"Netplay：开机玩家使用 P1，另一位玩家使用 P2");return;}
        for(Lease l:st.leases.values())if(l.player.equals(p.getUUID())){
            if(l.console!=c||l.port!=requestedPort||!validLease(p,l,false)||!authorizedController(p,l)||!validLease(p,l,false)){feedback(p,"请操作原手柄的插口，或先归还已有手柄");return;}
            if(held(p,l)){returnController(p.getServer(),l);return;}
            if(s==null||s.ports[requestedPort]==l){reclaim(p,l);return;}
            if(requestedPort>=SfcCartridgeData.maxPlayers(c.insertedCartridge())){feedback(p,"此卡带不启用这个控制端口；手柄仍保留");return;}
            if(s.clock==null){feedback(p,"手柄已借出，等待主机就绪后再点手柄接入控制");return;}
            if(s.host.player.equals(p.getUUID())){attachHostController(p,s,l);return;}
            requestJoin(p,s,requestedPort);return;
        }
        if(s==null){
            if(!physicalControllerAllowed(p,c)||st.sessions.containsKey(c.hardwareId()))return;
            if(grant(p,c,requestedPort,st)==null){feedback(p,"该手柄已借出，或请腾出一只手");return;}
            feedback(p,"已借出 P"+(requestedPort+1)+" 手柄；不会自动开机，请按主机电源");return;
        }
        if(s.clock==null){feedback(p,"请等待主机就绪");return;}
        if(requestedPort>=SfcCartridgeData.maxPlayers(c.insertedCartridge())){feedback(p,"这张卡带只允许一个控制端口");return;}
        if(s.ports[requestedPort]!=null||s.join!=null){feedback(p,"此手柄已有人使用，或正在同步另一位玩家");return;}
        if(!s.host.player.equals(p.getUUID())){requestJoin(p,s,requestedPort);return;}
        if(!hostValid(p,s)||preflight(p,c,true)==null||st.sessions.get(c.hardwareId())!=s||s.join!=null||s.ports[requestedPort]!=null||!candidate(p,s,false))return;
        Lease lease=grant(p,c,requestedPort,st);if(lease==null)return;
        s.ports[requestedPort]=lease;s.inputs[requestedPort]=new SfcInputTimeline();s.health.startPort(requestedPort,st.tick);
        SfcHomeNetwork.send(p,new SfcHomeNetwork.Control(s.id,s.epoch,lease.id,requestedPort,true));
        feedback(p,"已领取 P"+(requestedPort+1)+"；归还只退出控制，主机继续运行");
    }
    private static void attachHostController(ServerPlayer p,Session s,Lease lease){
        State st=state(p.getServer());
        if(NETPLAY.containsKey(s)&&lease.port!=0){feedback(p,"Netplay 开机玩家请使用 P1 手柄");return;}
        if(!hostValid(p,s)||st.sessions.get(s.connection.consoleId())!=s||s.join!=null||s.ports[lease.port]!=null
                ||lease.port>=SfcCartridgeData.maxPlayers(((SfcHomeConsoleBlockEntity)s.connection.console()).insertedCartridge())
                ||!endpointFacts(p,lease,s.connection)||!authorizedStart(p,lease,s.connection)||!hostValid(p,s)||!endpointFacts(p,lease,s.connection)
                ||st.sessions.get(s.connection.consoleId())!=s||s.join!=null||s.ports[lease.port]!=null
                ||lease.port>=SfcCartridgeData.maxPlayers(((SfcHomeConsoleBlockEntity)s.connection.console()).insertedCartridge()))return;
        s.ports[lease.port]=lease;s.inputs[lease.port]=new SfcInputTimeline();s.health.startPort(lease.port,st.tick);
        SfcHomeNetwork.send(p,new SfcHomeNetwork.Control(s.id,s.epoch,lease.id,lease.port,true));feedback(p,"已接入 P"+(lease.port+1)+" 控制");
    }
    private static Lease playerLease(State st,UUID player){for(Lease l:st.leases.values())if(l.player.equals(player))return l;return null;}
    private static Lease grant(ServerPlayer p,SfcHomeConsoleBlockEntity c,int port,State st){
        InteractionHand free=freeHand(p);if(free==null||!cableReach(p,c)||st.leases.size()>=8||st.leases.values().stream().anyMatch(l->l.player.equals(p.getUUID())||l.console==c&&l.port==port))return null;
        UUID id=UUID.randomUUID();ItemStack stack=SfcControllerData.create(id,port);Lease lease=new Lease(id,p,c,port,stack);st.leases.put(id,lease);p.setItemInHand(free,stack);c.setControllerVisual(port,p.getUUID(),id);p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_TAKE);return lease;
    }
    public static void useController(ServerPlayer p,InteractionHand hand){if(INTERACTING.get())return;State st=state(p.getServer());if(!st.interactions.allow(p.getUUID(),st.tick))return;Lease l=st.leases.get(SfcControllerData.leaseId(p.getItemInHand(hand)));if(l==null||!validLease(p,l,true)){feedback(p,"手柄已失效");return;}feedback(p,st.sessions.containsKey(l.console.hardwareId())?"右键原手柄位置或主机机身归还；收进物品栏只暂停输入":"手柄已借出；按主机电源开机，右键原手柄位置或主机机身归还");}
    public static InteractionResult useControllerOn(ServerPlayer p,InteractionHand hand,BlockPos pos,BlockHitResult hit){
        if(INTERACTING.get())return InteractionResult.CONSUME;
        var button=HomeApplianceService.tryButton(p,pos,hand,hit);if(button!=InteractionResult.PASS)return button;
        var level=p.serverLevel();if(!level.hasChunkAt(pos))return InteractionResult.FAIL;
        var clicked=level.getBlockEntity(pos);BlockPos tv=HomeTvStructure.resolveAnchor(level,pos);
        if(!(clicked instanceof SfcHomeConsoleBlockEntity)&&tv==null)return InteractionResult.PASS;
        if(tv!=null){feedback(p,"请右键绑定主机的机身归还手柄");return InteractionResult.CONSUME;}
        State st=state(p.getServer());if(!st.interactions.allow(p.getUUID(),st.tick))return InteractionResult.CONSUME;
        ItemStack item=p.getItemInHand(hand);Lease lease=st.leases.get(SfcControllerData.leaseId(item));
        if(lease==null||!validLease(p,lease,true)||lease.stack!=item){feedback(p,"手柄已失效");return InteractionResult.CONSUME;}
        if(clicked==lease.console&&!st.sessions.containsKey(lease.console.hardwareId())){
            if(!physicalControllerAllowed(p,lease.console)||!validLease(p,lease,true)||p.getItemInHand(hand)!=item||level.getBlockEntity(pos)!=clicked)return InteractionResult.FAIL;
            returnController(p.getServer(),lease);return InteractionResult.CONSUME;
        }
        var found=HomeSystems.connection(level,lease.console.getBlockPos());
        if(found.isEmpty()||(clicked!=lease.console&&!found.get().television().getBlockPos().equals(tv))){feedback(p,"请右键这个手柄绑定主机的机身");return InteractionResult.CONSUME;}
        var connection=found.get();INTERACTING.set(true);
        try{
            if(!hit.getBlockPos().equals(pos)||!level.mayInteract(p,pos)||!level.getWorldBorder().isWithinBounds(pos)||p.distanceToSqr(pos.getCenter())>64)return InteractionResult.FAIL;
            var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,hand,pos,hit));
            if(event.isCanceled()||event.getUseBlock()==TriState.FALSE||event.getUseItem()==TriState.FALSE
                    ||level.getBlockEntity(pos)!=clicked||p.getItemInHand(hand)!=item||!Objects.equals(tv,HomeTvStructure.resolveAnchor(level,pos))
                    ||!level.mayInteract(p,pos)||p.distanceToSqr(pos.getCenter())>64||!level.getWorldBorder().isWithinBounds(pos)||!validLease(p,lease,true)||!authorizedStart(p,lease,connection)
                    ||level.getBlockEntity(pos)!=clicked||p.getItemInHand(hand)!=item||!Objects.equals(tv,HomeTvStructure.resolveAnchor(level,pos))
                    ||!level.mayInteract(p,pos)||p.distanceToSqr(pos.getCenter())>64||!level.getWorldBorder().isWithinBounds(pos)||!validLease(p,lease,true)||!HomeSystems.isCurrent(connection))return InteractionResult.FAIL;
            returnController(p.getServer(),lease);return InteractionResult.CONSUME;
        }catch(RuntimeException rejected){feedback(p,"本次归还已取消");return InteractionResult.FAIL;}finally{INTERACTING.remove();}
    }
    private static String returnMessage(Lease l){return "P"+(l.port+1)+" 手柄已归还";}
    private static void returnController(MinecraftServer server,Lease lease){
        State st=STATES.get(server);if(st==null||st.leases.get(lease.id)!=lease)return;
        release(server,lease,returnMessage(lease));
        if(!st.leases.containsKey(lease.id)&&!lease.console.isRemoved()&&lease.console.getLevel() instanceof ServerLevel level
                &&level.hasChunkAt(lease.console.getBlockPos())&&level.getBlockEntity(lease.console.getBlockPos())==lease.console)
            HomeInteractionSounds.play(level,lease.console.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_RETURN);
    }
    private static void tossedController(ItemTossEvent event){
        if(event.isCanceled()||!(event.getPlayer() instanceof ServerPlayer player))return;
        ItemStack dropped=event.getEntity().getItem();if(!SfcControllerData.isController(dropped))return;
        State st=STATES.get(player.getServer());Lease lease=st==null?null:st.leases.get(SfcControllerData.leaseId(dropped));
        boolean genuine=lease!=null&&lease.connection==player.connection.getConnection()
                &&lease.authority.accepts(player.getUUID(),SfcControllerData.leaseId(dropped))
                &&lease.authority.itemMatches(true,SfcControllerData.leaseId(dropped),SfcControllerData.port(dropped),dropped.getCount())
                &&SfcControllerInventory.removedForToss(lease.id,personalItems(player),player.containerMenu.getCarried(),dropped,
                    item->SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null,ItemStack::getCount);
        try{if(genuine)returnController(player.getServer(),lease);}
        finally{dropped.setCount(0);event.getEntity().discard();event.setCanceled(true);}
    }
    /** Wired receipts never remain pickup-able after death, commands, or an old drop. */
    public static boolean discardDroppedController(net.minecraft.world.entity.item.ItemEntity entity){
        if(!(entity.level() instanceof ServerLevel)||!SfcControllerData.isController(entity.getItem()))return false;
        entity.getItem().setCount(0);entity.discard();return true;
    }
    private static InteractionHand freeHand(ServerPlayer p){return p.getMainHandItem().isEmpty()?InteractionHand.MAIN_HAND:p.getOffhandItem().isEmpty()?InteractionHand.OFF_HAND:null;}
    private static boolean held(ServerPlayer p,Lease l){return p.getMainHandItem()==l.stack||p.getOffhandItem()==l.stack;}
    private static List<ItemStack> personalItems(ServerPlayer p){var items=new ArrayList<ItemStack>();for(int i=0;i<p.getInventory().getContainerSize();i++)items.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)items.add(slot.getItem());items.add(p.containerMenu.getCarried());return items;}
    private static boolean cableReach(ServerPlayer p,SfcHomeConsoleBlockEntity c){return p.serverLevel()==c.getLevel()&&SfcControllerAuthority.withinCableDistance(p.distanceToSqr(c.getBlockPos().getCenter()));}
    private static boolean physicalControllerAllowed(ServerPlayer p,SfcHomeConsoleBlockEntity c){
        if(!basic(p,c)||!cableReach(p,c)||!p.serverLevel().getWorldBorder().isWithinBounds(c.getBlockPos()))return false;
        boolean previous=INTERACTING.get();INTERACTING.set(true);
        try{var pos=c.getBlockPos();var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,new BlockHitResult(pos.getCenter(),net.minecraft.core.Direction.UP,pos,false)));
            return !e.isCanceled()&&e.getUseBlock()!=TriState.FALSE&&e.getUseItem()!=TriState.FALSE&&basic(p,c)&&cableReach(p,c)&&p.serverLevel().getWorldBorder().isWithinBounds(pos);
        }catch(RuntimeException denied){return false;}finally{if(previous)INTERACTING.set(true);else INTERACTING.remove();}
    }
    private static boolean authorizedController(ServerPlayer p,Lease lease){var s=state(p.getServer()).sessions.get(lease.console.hardwareId());return s==null?physicalControllerAllowed(p,lease.console):s.connection.console()==lease.console&&authorizedStart(p,lease,s.connection);}
    private static void removeControllerCopies(ServerPlayer p,UUID lease){
        var items=personalItems(p);items.add(p.inventoryMenu.getCarried());for(var slot:p.containerMenu.slots)items.add(slot.getItem());
        var slots=new ArrayList<net.minecraft.world.inventory.Slot>();slots.addAll(p.inventoryMenu.slots);slots.addAll(p.containerMenu.slots);
        slots.removeIf(slot->!SfcControllerData.isController(slot.getItem())||!lease.equals(SfcControllerData.leaseId(slot.getItem())));
        SfcControllerInventory.revoke(lease,items,item->SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null,item->item.setCount(0));
        // A bound loan is not a storage item. Mark any currently open destination dirty too.
        for(var slot:slots)slot.setChanged();
        p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();if(p.containerMenu!=p.inventoryMenu)p.containerMenu.broadcastChanges();
    }
    private static boolean validLease(ServerPlayer p,Lease l,boolean requireHeld){
        if(!l.authority.accepts(p.getUUID(),l.id)||l.connection!=p.connection.getConnection()||state(p.getServer()).leases.get(l.id)!=l||!basic(p,l.console)||!cableReach(p,l.console))return false;
        if(p.containerMenu!=p.inventoryMenu)for(var slot:p.containerMenu.slots)if(slot.container!=p.getInventory()&&l.id.equals(SfcControllerData.leaseId(slot.getItem())))return false;
        ItemStack unique=SfcControllerInventory.unique(l.id,personalItems(p),s->SfcControllerData.isController(s)?SfcControllerData.leaseId(s):null,ItemStack::getCount);
        if(unique==null||!l.authority.itemMatches(SfcControllerData.isController(unique),SfcControllerData.leaseId(unique),SfcControllerData.port(unique),unique.getCount()))return false;
        // Cursor/slot moves can replace the ItemStack object. Rebind only after proving unique personal ownership.
        l.stack=unique;return l.authority.inventoryMatches(1,held(p,l),requireHeld);
    }
    private static void reclaim(ServerPlayer p,Lease lease){
        if(held(p,lease)){feedback(p,"已经持有这台主机的手柄；关机时只借出，不会启动游戏");return;}
        InteractionHand hand=freeHand(p);ItemStack item=lease.stack;
        if(hand==null||p.containerMenu.getCarried()==item){feedback(p,"请放下光标中的物品并腾出一只手");return;}
        for(int slot=0;slot<p.getInventory().getContainerSize();slot++)if(p.getInventory().getItem(slot)==item){p.getInventory().setItem(slot,ItemStack.EMPTY);p.setItemInHand(hand,item);p.getInventory().setChanged();p.containerMenu.broadcastChanges();feedback(p,state(p.getServer()).sessions.containsKey(lease.console.hardwareId())?"已重新拿起原手柄，继续本局":"已拿起原手柄；请按主机电源开机");return;}
        feedback(p,"请从个人物品栏拿起原手柄");
    }
    public static void validateControllerItem(ServerPlayer p,ItemStack item){if(!SfcControllerData.isController(item))return;Lease l=state(p.getServer()).leases.get(SfcControllerData.leaseId(item));if(l==null||!l.player.equals(p.getUUID())||!l.authority.itemMatches(true,SfcControllerData.leaseId(item),SfcControllerData.port(item),item.getCount()))item.setCount(0);}
    private static HomeSystems.Connection preflight(ServerPlayer p,SfcHomeConsoleBlockEntity console,boolean notify){
        if(!basic(p,console))return null;
        if(console.romSha().isEmpty()||!SfcCartridgeData.supported(console.insertedCartridge())){if(notify)feedback(p,"请先插入已写入游戏的 SFC 卡带");return null;}
        var found=HomeSystems.connection(p.serverLevel(),console.getBlockPos());if(found.isEmpty()){if(notify)feedback(p,"请先连接电视的视频线，再按主机电源");return null;}
        var c=found.get();ItemStack main=p.getMainHandItem(),off=p.getOffhandItem();var card=console.insertedCartridge();boolean previous=INTERACTING.get();INTERACTING.set(true);
        try{
            for(BlockPos pos:List.of(c.console().getBlockPos(),c.television().getBlockPos())){
                if(!basic(p,console)||!HomeSystems.isCurrent(c)||!p.serverLevel().mayInteract(p,pos)||!p.serverLevel().getWorldBorder().isWithinBounds(pos))return null;
                var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,new BlockHitResult(pos.getCenter(),net.minecraft.core.Direction.UP,pos,false)));
                if(event.isCanceled()||event.getUseBlock()==TriState.FALSE||event.getUseItem()==TriState.FALSE)return null;
            }
            if(!basic(p,console)||!HomeSystems.isCurrent(c)||p.getMainHandItem()!=main||p.getOffhandItem()!=off||!ItemStack.matches(card,console.insertedCartridge())
                    ||!p.serverLevel().mayInteract(p,c.console().getBlockPos())||!p.serverLevel().mayInteract(p,c.television().getBlockPos())
                    ||!p.serverLevel().getWorldBorder().isWithinBounds(c.console().getBlockPos())||!p.serverLevel().getWorldBorder().isWithinBounds(c.television().getBlockPos()))return null;
            return c;
        }catch(RuntimeException rejected){if(notify)feedback(p,"主机或电视的交互权限已失效");return null;}finally{if(previous)INTERACTING.set(true);else INTERACTING.remove();}
    }
    private static boolean powerOn(ServerPlayer p,HomeSystems.Connection connection){
        if(!(connection.console() instanceof SfcHomeConsoleBlockEntity c))return false;State st=state(p.getServer());
        if(!modeSupported(c)){feedback(p,hostedMode(c)?Objects.requireNonNullElse(hostedUnavailable(p.serverLevel(),c),"服务端托管不可用"):HomeSyncPolicy.unavailable(c.synchronizationMode()));return false;}
        if(st.stopping.containsKey(c.hardwareId())){feedback(p,"服务端核心仍在保存并停止，请稍后再开机或更改模式；手柄仍保留");return false;}
if(st.sessions.containsKey(c.hardwareId())||st.sessions.size()+st.stopping.size()>=4||st.sessions.values().stream().anyMatch(s->s.host.player.equals(p.getUUID()))||st.leases.values().stream().anyMatch(l->l.player.equals(p.getUUID())&&l.console!=c)){feedback(p,"请先关闭正在托管的主机或归还另一台主机的手柄");return false;}
        var card=c.insertedCartridge();String rom=c.romSha();var mode=c.synchronizationMode();var checked=preflight(p,c,true);
        if(checked==null||checked.console()!=connection.console()||!checked.linkId().equals(connection.linkId())||!ItemStack.matches(card,c.insertedCartridge())||!rom.equals(c.romSha())
                ||c.synchronizationMode()!=mode||!modeSupported(c)||st.sessions.containsKey(c.hardwareId())||st.sessions.size()+st.stopping.size()>=4||st.sessions.values().stream().anyMatch(s->s.host.player.equals(p.getUUID()))||st.leases.values().stream().anyMatch(l->l.player.equals(p.getUUID())&&l.console!=c))return false;
        long id=++st.nextSession;if(id<=0)throw new IllegalStateException("Session IDs exhausted");
        Session s=new Session(id,1,checked,rom,new Host(p),new Lease[2],st.tick,mode);
        if(c.netplayExperimental()){
            if(mode!=cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC){feedback(p,"请重新选择 Netplay 模式");return false;}
            long wire=NetplayNetwork.nextAddonId();var relay=NetplayNetwork.room(wire,p.connection.getConnection());
            try{
                var identity=NetplaySaveState.identity(cn.piq.sfchome.core.SfcNetplayProfile.profile(),rom,Map.of());
                UUID cardId=cn.piq.sfchome.data.SfcCartridgeData.id(card);int saveMode=cn.piq.sfchome.data.SfcCartridgeData.saveMode(card);
                if(cardId==null)throw new IllegalStateException("卡带身份缺失，请先放入卡带工作台");
                String owner=saveMode==1?"sfc-personal|"+p.getUUID():"sfc-card|"+cardId;
                String slot=owner+"|"+identity.profile()+"|"+identity.content();
                NetplaySaveServer.open(p.getServer(),wire,p.connection.getConnection(),relay.grant(p.connection.getConnection(),true).id(),identity,slot,
                    saveMode==0?null:NetplaySaveServer.file(NetplaySaveServer.directory(p.getServer(),owner,identity),identity));
                NETPLAY.put(s,new NetplayRun(wire,relay));
            }catch(RuntimeException failure){NetplayNetwork.retire(relay);feedback(p,"Netplay 存档未就绪："+failure.getMessage());return false;}
        }
        s.multiplayer=true; // Public playback permits vacant ports; stored card/approval limits still apply.
        if(mode==cn.piq.fcarcade.cabinet.CabinetSyncMode.SERVER_MEDIA){
            var permit=cn.piq.fcarcade.server.hosted.HostedServerLimits.tryAcquire(p.getServer());
            if(permit==null){feedback(p,"服务端托管未启用或全服容量已满；主机未开机");return false;}
            boolean transferred=false;
            try{s.hosted=openHosted(s,permit);transferred=true;}catch(RuntimeException|LinkageError failure){feedback(p,"服务端托管无法启动；主机未开机");return false;}finally{if(!transferred)permit.close();}
        }
        st.sessions.put(c.hardwareId(),s);sendRuntime(p,s,null);
        Lease borrowed=playerLease(st,p.getUUID());if(borrowed!=null&&borrowed.console==c)attachHostController(p,s,borrowed);
        feedback(p,s.playerMedia()?"SFC 玩家串流正在开机；你负责运行，退出后安全停机；恢复备份仅保存在本机":"SFC 正在开机；未领取手柄时只播放，不占用操作键");return true;
    }
    private static void sendRuntime(ServerPlayer p,Session s,Lease lease){
        var c=s.connection;boolean host=s.host.player.equals(p.getUUID());var run=NETPLAY.get(s);
        var message=new SfcHomeNetwork.Session(s.id,s.epoch,c.level().dimension().location(),c.console().getBlockPos(),c.consoleId(),c.television().getBlockPos(),c.televisionId(),c.linkId(),s.rom,SfcHomeNetwork.CORE_BUILD,host?-1:lease.port,host?s.host.id:lease.id,host,run==null?s.mode.ordinal():3,s.watchSource,s.host.id);
        if(run==null){SfcHomeNetwork.send(p,message);return;}
        if(!host&&(lease==null||lease.port!=1||!endpointFacts(p,lease,s.connection)))return;
        var ticket=run.room().grant(p.connection.getConnection(),true);if(ticket==null)return;
        NetplayNetwork.authorize(run.wire(),p.connection.getConnection(),run.room());
        SfcHomeNetwork.send(p,new SfcHomeNetwork.NetplayStart(message,run.wire(),ticket.id()));
    }
    private static void reset(ServerPlayer p,HomeSystems.Connection connection){
        State st=state(p.getServer());Session old=st.sessions.get(connection.consoleId());if(old==null||old.connection.console()!=connection.console()||old.epoch==Integer.MAX_VALUE)return;
        if(NETPLAY.containsKey(old)){feedback(p,"Netplay 请关机后重新开机");return;}
        if(!(connection.console() instanceof SfcHomeConsoleBlockEntity c)||preflight(p,c,true)==null||st.sessions.get(c.hardwareId())!=old)return;
        ServerPlayer host=p.getServer().getPlayerList().getPlayer(old.host.player);if(!hostValid(host,old)){stop(st,c.hardwareId(),"运行宿主已离开");return;}
        if(old.hosted!=null){
            if(!old.hosted.reset()){feedback(p,"服务端核心尚未就绪或不支持重置；主机与手柄未改变");return;}
            abortJoin(st,old,"主机正在重置，手柄仍保留");for(var input:old.inputs)input.clear();old.health.start(st.tick);
            for(var recipient:recipients(p.getServer(),old)){boolean owner=host(recipient,old);int port=port(old,recipient.getUUID());if(owner||port>=0)SfcHomeNetwork.send(recipient,new cn.piq.sfchome.net.SfcHostedNetwork.Reset(old.id,old.epoch,owner?old.host.id:old.ports[port].id));}
            HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.RESET);feedback(p,"SFC 服务端核心已请求重置；控制租约保留");return;
        }
        finishRepair(old);abortJoin(st,old,"主机正在重置");for(var player:recipients(p.getServer(),old))SfcHomeNetwork.send(player,new SfcHomeNetwork.Stopped(old.id,old.epoch,"主机已重置"));
        Session next=new Session(old.id,old.epoch+1,old.connection,old.rom,new Host(host),old.ports.clone(),st.tick,old.mode);next.multiplayer=old.multiplayer;st.sessions.put(c.hardwareId(),next);sendRuntime(host,next,null);
        for(Lease l:next.ports)if(l!=null){ServerPlayer owner=p.getServer().getPlayerList().getPlayer(l.player);if(owner==null)continue;if(!l.player.equals(next.host.player))sendRuntime(owner,next,l);else SfcHomeNetwork.send(owner,new SfcHomeNetwork.Control(next.id,next.epoch,l.id,l.port,true));}
        HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.RESET);
    }
    private static boolean hostValid(ServerPlayer p,Session s){return p!=null&&p.getServer().isSameThread()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator()
            &&s.host.player.equals(p.getUUID())&&s.host.connection==p.connection.getConnection()&&s.host.connection.isConnected()&&p.serverLevel()==s.connection.level()&&HomeSystems.isCurrent(s.connection)
            &&s.rom.equals(((SfcHomeConsoleBlockEntity)s.connection.console()).romSha())&&p.serverLevel().mayInteract(p,s.connection.console().getBlockPos())&&p.serverLevel().mayInteract(p,s.connection.television().getBlockPos())
            &&p.serverLevel().getWorldBorder().isWithinBounds(s.connection.console().getBlockPos())&&p.serverLevel().getWorldBorder().isWithinBounds(s.connection.television().getBlockPos());}
    private static boolean host(ServerPlayer p,Session s){return s!=null&&hostValid(p,s);}
    private static List<ServerPlayer> recipients(MinecraftServer server,Session s){var ids=new LinkedHashSet<UUID>();ids.add(s.host.player);for(Lease l:s.ports)if(l!=null)ids.add(l.player);var out=new ArrayList<ServerPlayer>();for(UUID id:ids){var p=server.getPlayerList().getPlayer(id);if(p!=null)out.add(p);}return out;}
    private static Session member(ServerPlayer p,long id,int epoch){for(Session s:state(p.getServer()).sessions.values())if(s.id==id&&s.epoch==epoch){if(host(p,s))return s;for(Lease l:s.ports)if(l!=null&&l.player.equals(p.getUUID())&&validLease(p,l,false)&&HomeSystems.isCurrent(s.connection))return s;if(s.join!=null&&s.join.second!=null&&s.join.second.player.equals(p.getUUID())&&validLease(p,s.join.second,false)&&HomeSystems.isCurrent(s.connection))return s;}return null;}
    private static boolean authorizedStart(ServerPlayer p,Lease lease,HomeSystems.Connection connection){
        if(INTERACTING.get())return protectedEndpoints(p,lease,connection);
        INTERACTING.set(true);try{return protectedEndpoints(p,lease,connection);}catch(RuntimeException ex){return false;}finally{INTERACTING.remove();}
    }
    private static boolean protectedEndpoints(ServerPlayer p,Lease lease,HomeSystems.Connection c){
        for(BlockPos pos:List.of(c.console().getBlockPos(),c.television().getBlockPos())){
            if(!validLease(p,lease,false)||!HomeSystems.isCurrent(c)||!p.serverLevel().getWorldBorder().isWithinBounds(pos)||!p.serverLevel().mayInteract(p,pos))return false;
            var hit=new BlockHitResult(pos.getCenter(),net.minecraft.core.Direction.UP,pos,false);
            var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,hit));
            if(event.isCanceled()||event.getUseBlock()==TriState.FALSE||event.getUseItem()==TriState.FALSE||!validLease(p,lease,false)||!HomeSystems.isCurrent(c))return false;
        }return endpointFacts(p,lease,c);
    }
    private static boolean endpointFacts(ServerPlayer p,Lease l,HomeSystems.Connection c){return validLease(p,l,false)&&HomeSystems.isCurrent(c)&&p.serverLevel().getWorldBorder().isWithinBounds(c.console().getBlockPos())&&p.serverLevel().getWorldBorder().isWithinBounds(c.television().getBlockPos())&&p.serverLevel().mayInteract(p,c.console().getBlockPos())&&p.serverLevel().mayInteract(p,c.television().getBlockPos());}
    private static int port(Session s,UUID p){for(int i=0;i<2;i++)if(s.ports[i]!=null&&s.ports[i].player.equals(p))return i;return -1;}
    public static void allowJoin(ServerPlayer p,SfcJoinNetwork.Allow packet){
        Session s=member(p,packet.session(),packet.epoch());
        if(!host(p,s))return;
        s.multiplayer=packet.enabled();if(!s.multiplayer)abortJoin(state(p.getServer()),s,"宿主已关闭手柄申请");
        feedback(p,s.multiplayer?"已允许其他玩家申请空闲手柄，由你审批":"保持私有；关机再开可重新选择");
    }
    private static boolean candidate(ServerPlayer p,Session s,boolean events){
        var console=(SfcHomeConsoleBlockEntity)s.connection.console();
        if(p==null||!basic(p,console)||!cableReach(p,console)||!candidateController(p,s)||!HomeSystems.isCurrent(s.connection))return false;
        State st=state(p.getServer());
        if(st.sessions.values().stream().anyMatch(v->v!=s&&v.host.player.equals(p.getUUID())))return false;
        for(BlockPos pos:List.of(s.connection.console().getBlockPos(),s.connection.television().getBlockPos())){
            if(!p.serverLevel().getWorldBorder().isWithinBounds(pos)||!p.serverLevel().mayInteract(p,pos))return false;
            if(events){boolean previous=INTERACTING.get();INTERACTING.set(true);try{
                var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,new BlockHitResult(pos.getCenter(),net.minecraft.core.Direction.UP,pos,false)));
                if(e.isCanceled()||e.getUseBlock()==TriState.FALSE||e.getUseItem()==TriState.FALSE)return false;
            }catch(RuntimeException rejected){return false;}finally{if(previous)INTERACTING.set(true);else INTERACTING.remove();}}
        }
        return basic(p,console)&&cableReach(p,console)&&HomeSystems.isCurrent(s.connection)&&candidateController(p,s)
                &&p.serverLevel().mayInteract(p,s.connection.console().getBlockPos())&&p.serverLevel().mayInteract(p,s.connection.television().getBlockPos())
                &&p.serverLevel().getWorldBorder().isWithinBounds(s.connection.console().getBlockPos())&&p.serverLevel().getWorldBorder().isWithinBounds(s.connection.television().getBlockPos())
                &&st.sessions.values().stream().noneMatch(v->v!=s&&v.host.player.equals(p.getUUID()));
    }
    private static boolean candidateController(ServerPlayer p,Session s){State st=state(p.getServer());Lease own=playerLease(st,p.getUUID());return own==null?freeHand(p)!=null&&st.leases.size()<8:own.console==s.connection.console()&&s.ports[own.port]==null&&validLease(p,own,false);}
    private static boolean joinValid(MinecraftServer server,Session s,boolean events){
        Joining j=s.join;if(j==null)return false;State st=state(server);var console=(SfcHomeConsoleBlockEntity)s.connection.console();
        if(st.sessions.get(s.connection.consoleId())!=s||!j.gate.live(st.tick)||!s.multiplayer||s.ports[j.port]!=null
                ||!s.host.id.equals(j.gate.hostLease)||!s.connection.consoleId().equals(j.gate.console)||!HomeSystems.isCurrent(s.connection)
                ||!s.rom.equals(console.romSha())||!ItemStack.matches(j.card,console.insertedCartridge())||j.port>=SfcCartridgeData.maxPlayers(j.card))return false;
        ServerPlayer host=server.getPlayerList().getPlayer(s.host.player),other=server.getPlayerList().getPlayer(j.gate.applicant);
        if(!hostValid(host,s))return false;
        boolean eligible=j.second==null?candidate(other,s,events):other!=null&&st.leases.get(j.second.id)==j.second&&endpointFacts(other,j.second,s.connection)&&(!events||authorizedStart(other,j.second,s.connection));
        // Protection event callbacks can synchronously stop a session or edit/remove its hardware.
        return eligible&&s.join==j&&st.sessions.get(s.connection.consoleId())==s&&j.gate.live(st.tick)&&s.multiplayer&&s.ports[j.port]==null
                &&hostValid(host,s)&&ItemStack.matches(j.card,console.insertedCartridge());
    }
    private static void requestJoin(ServerPlayer p,Session s,int requestedPort){
        State st=state(p.getServer());
        if(NETPLAY.containsKey(s)&&requestedPort!=1){feedback(p,"Netplay 加入玩家使用 P2 手柄");return;}
        if(s.repairs.active()!=null){feedback(p,"一个控制端正在同步，请稍后申请；主机仍在运行");return;}
        Lease reserved=playerLease(st,p.getUUID());if(reserved!=null&&(reserved.console!=s.connection.console()||reserved.port!=requestedPort)){feedback(p,"请点已借出手柄的原插口申请");return;}
        if(s.clock==null){feedback(p,"主机正在加载，请稍后申请");return;}
        if(!s.multiplayer){feedback(p,"运行宿主尚未允许手柄申请");return;}
        if(requestedPort<0||requestedPort>=SfcCartridgeData.maxPlayers(((SfcHomeConsoleBlockEntity)s.connection.console()).insertedCartridge())||s.ports[requestedPort]!=null){feedback(p,"此手柄不可领取");return;}
        if(s.join!=null){feedback(p,s.join.gate.applicant.equals(p.getUUID())?"你的申请正在处理中":"运行宿主正在处理另一份申请");return;}
        if(st.sessions.values().stream().anyMatch(v->v.join!=null&&v.join.gate.applicant.equals(p.getUUID()))){feedback(p,"请先等当前加入申请结束");return;}
        if(!candidate(p,s,true)){feedback(p,"申请需要一只手空闲、有效距离以及主机和电视交互权限");return;}
        ServerPlayer host=p.getServer().getPlayerList().getPlayer(s.host.player);
        if(!hostValid(host,s)||st.sessions.get(s.connection.consoleId())!=s||s.join!=null||!s.multiplayer||s.ports[requestedPort]!=null
                ||requestedPort>=SfcCartridgeData.maxPlayers(((SfcHomeConsoleBlockEntity)s.connection.console()).insertedCartridge())
                ||st.sessions.values().stream().anyMatch(v->v.join!=null&&v.join.gate.applicant.equals(p.getUUID())))return;
        s.join=new Joining(new SfcJoinGate(UUID.randomUUID(),host.getUUID(),p.getUUID(),s.host.id,s.connection.consoleId(),st.tick),((SfcHomeConsoleBlockEntity)s.connection.console()).insertedCartridge(),requestedPort);
        // Disabling confirmation skips only the host dialog, never permissions,
        // the current card/endpoint identity checks, or the snapshot handshake.
        if(!s.connection.console().joinApprovalRequired()){
            decideJoin(host,new SfcJoinNetwork.Decision(s.id,s.epoch,s.join.gate.token,true));
            return;
        }
        SfcHomeNetwork.send(host,new SfcJoinNetwork.Approval(s.id,s.epoch,s.join.gate.token,p.getGameProfile().getName()+" → P"+(requestedPort+1)));feedback(p,"申请已发给运行宿主；批准后领取手柄并同步当前进度");
    }
    public static void decideJoin(ServerPlayer p,SfcJoinNetwork.Decision packet){
        Session s=member(p,packet.session(),packet.epoch());if(!host(p,s)||s.join==null)return;
        State st=state(p.getServer());Joining j=s.join;
        if(!j.gate.host.equals(p.getUUID())||!j.gate.token.equals(packet.token())||j.gate.phase()!=SfcJoinGate.Phase.APPROVAL)return;
        if(!joinValid(p.getServer(),s,true)){abortJoin(st,s,"申请已失效，主机继续");return;}
        if(!j.gate.approve(p.getUUID(),packet.token(),packet.accepted(),st.tick))return;
        if(!packet.accepted()){abortJoin(st,s,"运行宿主拒绝了加入申请");return;}
        ServerPlayer other=p.getServer().getPlayerList().getPlayer(j.gate.applicant);
        if(!joinValid(p.getServer(),s,true)||!candidate(other,s,false)){abortJoin(st,s,"申请者手已占用或权限已改变，加入取消");return;}
        j.second=playerLease(st,other.getUUID());if(j.second==null)j.second=grant(other,(SfcHomeConsoleBlockEntity)s.connection.console(),j.port,st);
        if(j.second==null||j.second.console!=s.connection.console()||j.second.port!=j.port||!validLease(other,j.second,false)){abortJoin(st,s,"手柄无法领取，加入取消");return;}
        sendRuntime(other,s,j.second);
        feedback(p,"已批准；申请者加载期间主机继续运行");feedback(other,s.playerMedia()?"运行宿主已批准；正在连接音画，无需下载 ROM 或启动本地核心":"运行宿主已批准；正在加载，随后同步当前进度");
    }
    private static void joinReady(ServerPlayer p,Session s,SfcHomeNetwork.Ready ready){
        State st=state(p.getServer());Joining j=s.join;if(j==null||j.gate.phase()!=SfcJoinGate.Phase.LOADING)return;
        if(s.hosted!=null||s.playerMedia()||NETPLAY.containsKey(s)){
            if(s.hosted!=null&&!s.hosted.ready()||s.playerMedia()&&(s.hostReady==null||s.clock==null)||!joinValid(p.getServer(),s,true)||!ready.romSha().equals(s.rom)||!ready.coreBuild().equals(SfcHomeNetwork.CORE_BUILD)
                    ||j.second==null||!endpointFacts(p,j.second,s.connection)||s.ports[j.port]!=null){abortJoin(st,s,"接收端或控制授权已改变，主机继续");return;}
            s.ports[j.port]=j.second;s.ready[j.port]=ready;s.inputs[j.port]=new SfcInputTimeline();s.health.startPort(j.port,st.tick);s.join=null;j.gate.close();
            for(UUID id:List.of(j.gate.host,j.gate.applicant)){var recipient=p.getServer().getPlayerList().getPlayer(id);if(recipient!=null)SfcHomeNetwork.send(recipient,new SfcJoinNetwork.Result(s.id,s.epoch,j.gate.token,true,NETPLAY.containsKey(s)?"已加入 Netplay 同一局，使用 P2 手柄":s.playerMedia()?"已接入主持玩家的音画与控制；不另开游戏":"已接入服务端同一局音画与控制"));}
            return;
        }
        if(!joinValid(p.getServer(),s,true)||!ready.romSha().equals(s.rom)||!ready.coreBuild().equals(SfcHomeNetwork.CORE_BUILD)
                ||s.hostReady==null||!ready.initialStateHash().equals(s.hostReady.initialStateHash())||Math.abs(ready.targetFps()-s.hostReady.targetFps())>0.000001){abortJoin(st,s,"ROM、核心、初始状态或权限不一致；主机继续");return;}
        if(st.transfer!=null){abortJoin(st,s,"另一台主机正在同步进度，请稍后重新申请");return;}
        if(!j.gate.capture(p.getUUID(),s.frame,st.tick))return;
        st.transfer=j.gate.token;j.ready=ready;s.inputs[0].clear();s.inputs[1].clear();
        SfcHomeNetwork.send(p.getServer().getPlayerList().getPlayer(j.gate.host),new SfcJoinNetwork.Capture(s.id,s.epoch,j.gate.token,s.frame));
        feedback(p,"正在同步主机当前进度，请稍候");
    }
    private static boolean paused(Session s){return s.join!=null&&(s.join.gate.phase()==SfcJoinGate.Phase.CAPTURE||s.join.gate.phase()==SfcJoinGate.Phase.APPLYING);}
    public static void joinUpload(ServerPlayer p,SfcJoinNetwork.State packet){
        Session s=member(p,packet.session(),packet.epoch());if(s==null||s.join==null)return;State st=state(p.getServer());Joining j=s.join;
        if(!j.gate.host.equals(p.getUUID())||!j.gate.token.equals(packet.token()))return;
        if(!joinValid(p.getServer(),s,true)||!Objects.equals(st.transfer,j.gate.token)
                ||!j.gate.append(p.getUUID(),packet.token(),packet.frame(),packet.total(),packet.offset(),packet.sha(),packet.data(),st.tick))abortJoin(st,s,"进度分片无效或权限改变；主机原局继续");
    }
    public static void joinApplied(ServerPlayer p,SfcJoinNetwork.Applied packet){
        Session s=member(p,packet.session(),packet.epoch());if(s==null||s.join==null)return;State st=state(p.getServer());Joining j=s.join;
        if(!j.gate.token.equals(packet.token())||(!j.gate.host.equals(p.getUUID())&&!j.gate.applicant.equals(p.getUUID())))return;
        if(!packet.success()){abortJoin(st,s,"客户端无法同步当前进度；主机原局继续");return;}
        if(!j.gate.applicant.equals(p.getUUID()))return;
        if(!joinValid(p.getServer(),s,true)||!Objects.equals(st.transfer,j.gate.token)||j.sent!= (j.gate.bytes()==null?-1:j.gate.bytes().length)
                ||!j.gate.commit(p.getUUID(),packet.token(),packet.frame(),packet.sha(),st.tick)){abortJoin(st,s,"申请者进度校验失败；主机原局继续");return;}
        s.ports[j.port]=j.second;s.ready[j.port]=j.ready;s.inputs[0].clear();s.inputs[1].clear();s.inputs[j.port]=new SfcInputTimeline();s.health.start(st.tick);s.clock=new SfcFrameClock(s.hostReady.targetFps());
        st.transfer=null;s.join=null;
        for(ServerPlayer player:recipients(p.getServer(),s))SfcHomeNetwork.send(player,new SfcJoinNetwork.Result(s.id,s.epoch,j.gate.token,true,"P"+(j.port+1)+" 已从当前进度加入；归还手柄不会关机"));
    }
    private static void abortJoin(State st,Session s,String reason){
        Joining j=s.join;if(j==null)return;boolean wasPaused=paused(s);s.join=null;j.gate.close();
        var netplay=NETPLAY.get(s);if(netplay!=null&&j.second!=null)netplay.room().revoke(j.second.connection);
        if(Objects.equals(st.transfer,j.gate.token))st.transfer=null;
        MinecraftServer server=s.connection.level().getServer();
        for(UUID id:List.of(j.gate.host,j.gate.applicant)){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)SfcHomeNetwork.send(p,new SfcJoinNetwork.Result(s.id,s.epoch,j.gate.token,false,reason));}
        if(j.second!=null){ServerPlayer p=server.getPlayerList().getPlayer(j.second.player);if(p!=null){SfcHomeNetwork.send(p,new SfcHomeNetwork.Stopped(s.id,s.epoch,reason));feedback(p,"加入已取消，手柄仍保留；可重新申请或右键主机归还");}}
        if(wasPaused){s.inputs[0].clear();s.inputs[1].clear();s.health.start(st.tick);if(s.hostReady!=null)s.clock=new SfcFrameClock(s.hostReady.targetFps());}
    }
    public static void ready(ServerPlayer p,SfcHomeNetwork.Ready r){
        Session s=member(p,r.sessionId(),r.epoch());if(s==null)return;
        if(s.join!=null&&s.join.second!=null&&s.join.second.player.equals(p.getUUID())){joinReady(p,s,r);return;}
        boolean isHost=host(p,s);int port=port(s,p.getUUID());if(isHost?s.hostReady!=null:port<0||s.ready[port]!=null)return;
        if(!r.romSha().equals(s.rom)||!r.coreBuild().equals(SfcHomeNetwork.CORE_BUILD)){stop(state(p.getServer()),s.connection.consoleId(),"ROM 或核心版本不一致");return;}
        try{new SfcFrameClock(r.targetFps());}catch(IllegalArgumentException ex){stop(state(p.getServer()),s.connection.consoleId(),"不支持此游戏帧率");return;}
        if(isHost)s.hostReady=r;else s.ready[port]=r;
        startClockIfReady(p.getServer(),s);
    }
    private static void startClockIfReady(MinecraftServer server,Session s){
        if(s.clock!=null||s.hostReady==null)return;
        if(s.hosted!=null){if(!s.hosted.ready())return;s.clock=new SfcFrameClock(60);s.health.start(state(server).tick);HomeApplianceService.refresh(s.connection.level(),s.connection.television().getBlockPos());return;}
        if(s.playerMedia()||NETPLAY.containsKey(s)){
            s.clock=new SfcFrameClock(s.hostReady.targetFps());s.health.start(state(server).tick);
            HomeApplianceService.refresh(s.connection.level(),s.connection.television().getBlockPos());return;
        }
        for(int i=0;i<2;i++)if(s.ports[i]!=null&&!s.ports[i].player.equals(s.host.player)){
            if(s.ready[i]==null)return;
            if(!s.hostReady.initialStateHash().equals(s.ready[i].initialStateHash())||Math.abs(s.hostReady.targetFps()-s.ready[i].targetFps())>0.000001){stop(state(server),s.connection.consoleId(),"运行端的初始状态不一致");return;}
        }
        s.clock=new SfcFrameClock(s.hostReady.targetFps());s.health.start(state(server).tick);
        HomeApplianceService.refresh(s.connection.level(),s.connection.television().getBlockPos());
        for(var p:recipients(server,s))feedback(p,"SFC 已运行；未领取手柄只观看，归还手柄不关机");
    }
    private static boolean controlLease(ServerPlayer p,Session s,UUID lease){
        if(s==null||lease==null)return false;
        for(Lease current:s.ports)if(current!=null&&current.authority.accepts(p.getUUID(),lease)&&validLease(p,current,false))return true;
        Lease candidate=s.join==null?null:s.join.second;
        return candidate!=null&&candidate.authority.accepts(p.getUUID(),lease)&&validLease(p,candidate,false);
    }
    public static void controllerReady(ServerPlayer p,SfcJoinNetwork.ControllerReady packet){
        var message=packet.ready();Session s=member(p,message.sessionId(),message.epoch());
        if(s!=null&&(host(p,s)&&s.host.id.equals(packet.lease())||!host(p,s)&&controlLease(p,s,packet.lease())))ready(p,message);
    }
    public static void controllerLeave(ServerPlayer p,SfcJoinNetwork.ControllerLeave packet){
        var message=packet.leave();Session s=member(p,message.sessionId(),message.epoch());
        if(s==null)return;
        if(host(p,s)&&s.host.id.equals(packet.lease()))stop(state(p.getServer()),s.connection.consoleId(),"后台运行端已退出，主机安全停机");
        else if(controlLease(p,s,packet.lease()))leave(p,message);
    }
    /** Old unleased input cannot control a powered device; both ports now carry their exact token. */
    public static void input(ServerPlayer p,SfcHomeNetwork.Input r){}
    public static void joinInput(ServerPlayer p,SfcJoinNetwork.ControllerInput packet){var r=packet.input();Session s=member(p,r.sessionId(),r.epoch());if(s==null)return;int port=port(s,p.getUUID());if(port<0||!s.ports[port].id.equals(packet.lease()))return;acceptInput(p,s,r,port);}
    private static void acceptInput(ServerPlayer p,Session s,SfcHomeNetwork.Input r,int port){
        State st=state(p.getServer());Lease lease=s.ports[port];if(lease==null||!lease.player.equals(p.getUUID())||!endpointFacts(p,lease,s.connection))return;
        // Keeping a personal controller preserves membership, not input permission. Consume its sequence
        // and heartbeat while forcing neutral so a late non-zero packet cannot revive an unheld port.
        var input=SfcControllerAuthority.input(held(p,lease)&&!s.repairs.isolated(p.getUUID()),r.buttonMask(),r.forceRelease());
        if(NETPLAY.containsKey(s)){if(!s.health.packet(port,st.tick)||input==null)release(p.getServer(),lease,"Netplay 手柄心跳无效");return;}
        if(!s.health.packet(port,st.tick)||input==null||!s.inputs[port].offer(r.sequence(),input.mask(),input.release()))release(p.getServer(),lease,"输入序列或频率异常，手柄已归还；主机继续");
        else if(input.release()&&s.hosted!=null)s.hosted.releasePort(port);
    }
    public static void leave(ServerPlayer p,SfcHomeNetwork.Leave r){Session s=member(p,r.sessionId(),r.epoch());if(s==null)return;if(s.join!=null&&s.join.gate.applicant.equals(p.getUUID())){abortJoin(state(p.getServer()),s,"申请者已取消加入，主机继续");return;}int port=port(s,p.getUUID());if(port>=0)release(p.getServer(),s.ports[port],"手柄已归还，主机继续运行");}
    public static boolean authorizedRom(ServerPlayer p,String hash){for(Session s:state(p.getServer()).sessions.values())if(s.rom.equals(hash)&&cn.piq.sfchome.net.SfcPlaybackMode.permitsRom(s.mode.ordinal(),host(p,s))&&member(p,s.id,s.epoch)!=null)return true;return false;}
    private static Session repairSession(ServerPlayer p,SfcRepairNetwork.Key key){
        Session s=member(p,key.session(),key.epoch());if(s==null||NETPLAY.containsKey(s)||s.clock==null||s.hosted!=null||s.playerMedia())return null;
        if(host(p,s))return s.host.id.equals(key.lease())?s:null;
        int port=port(s,p.getUUID());return port>=0&&s.ports[port].id.equals(key.lease())&&endpointFacts(p,s.ports[port],s.connection)?s:null;
    }
    private static SfcRepairNetwork.Key repairKey(Session s,SfcRepairLedger.Repair r,boolean host,int frame){return new SfcRepairNetwork.Key(s.id,s.epoch,host?s.host.id:r.lease,r.token,frame);}
    public static void repairDigest(ServerPlayer p,SfcRepairNetwork.Digest packet){
        Session s=repairSession(p,packet.key());if(s==null||s.join!=null||s.repairs.isolated(p.getUUID()))return;State st=state(p.getServer());
        for(var mismatch:s.repairs.report(p.getUUID(),host(p,s),packet.key().frame(),packet.sha())){
            if(s.repairs.active()!=null)break;startRepair(st,s,mismatch);
        }
    }
    public static void repairFault(ServerPlayer p,SfcRepairNetwork.Fault packet){Session s=repairSession(p,packet.key());if(s==null||host(p,s)||s.repairs.active()!=null||packet.key().frame()>s.frame)return;
        var mismatch=s.repairs.latest(p.getUUID());if(s.join!=null||mismatch==null){int port=port(s,p.getUUID());if(port>=0)release(p.getServer(),s.ports[port],"尚无可用检查点，已退出异常端；主机继续");return;}startRepair(state(p.getServer()),s,mismatch);}
    private static void startRepair(State st,Session s,SfcRepairLedger.Mismatch mismatch){
            int port=port(s,mismatch.player());if(port<0||mismatch.player().equals(s.host.player))return;MinecraftServer server=s.connection.level().getServer();
            Lease lease=s.ports[port];var repair=s.repairs.start(mismatch,lease.id,st.tick);
            if(repair==null){release(server,lease,"控制端重复不同步，已退出控制；主机继续");return;}
            s.inputs[port].clear();ServerPlayer target=server.getPlayerList().getPlayer(lease.player),owner=server.getPlayerList().getPlayer(s.host.player);
            if(target==null||owner==null){failRepair(st,s,"重同步连接失效");return;}
            SfcHomeNetwork.send(target,new SfcRepairNetwork.Begin(repairKey(s,repair,false,repair.frame)));
            SfcHomeNetwork.send(owner,new SfcRepairNetwork.Request(repairKey(s,repair,true,repair.frame)));
            feedback(target,"检测到本端状态偏差，正在修复；其他玩家继续运行");
    }
    public static void repairUpload(ServerPlayer p,SfcRepairNetwork.State packet){
        Session s=repairSession(p,packet.key());if(s==null||!host(p,s))return;var r=s.repairs.active();if(r==null||!r.token.equals(packet.key().token()))return;
        if(!r.append(packet.key().token(),packet.key().frame(),packet.total(),packet.offset(),packet.sha(),packet.data(),state(p.getServer()).tick))failRepair(state(p.getServer()),s,"重同步状态无效，已退出异常端；主机继续");
    }
    public static void repairRestored(ServerPlayer p,SfcRepairNetwork.Restored packet){
        Session s=repairSession(p,packet.key());if(s==null)return;var r=s.repairs.active();if(r==null||!r.player.equals(p.getUUID())||!r.token.equals(packet.key().token())||!r.lease.equals(packet.key().lease()))return;
        if(!r.restored(packet.key().token(),packet.key().frame(),packet.sha(),packet.success()))failRepair(state(p.getServer()),s,"重同步状态校验失败；主机继续");
    }
    public static void repairDone(ServerPlayer p,SfcRepairNetwork.Done packet){
        Session s=repairSession(p,packet.key());if(s==null)return;var r=s.repairs.active();if(r==null||!r.token.equals(packet.key().token()))return;
        if(host(p,s)){if(!packet.success())failRepair(state(p.getServer()),s,"宿主检查点已过期；异常端退出，主机继续");return;}
        if(!r.player.equals(p.getUUID())||!r.lease.equals(packet.key().lease()))return;
        if(!r.done(packet.key().token(),packet.key().frame(),packet.success())){failRepair(state(p.getServer()),s,"重同步追帧未完成；主机继续");return;}
        finishRepair(s);feedback(p,"本端已恢复到主机进度，控制已恢复");
    }
    private static void finishRepair(Session s){var r=s.repairs.active();if(r==null)return;var server=s.connection.level().getServer();
        var owner=server.getPlayerList().getPlayer(s.host.player);if(owner!=null)SfcHomeNetwork.send(owner,new SfcRepairNetwork.Cancel(repairKey(s,r,true,r.frame)));
        var target=server.getPlayerList().getPlayer(r.player);if(target!=null)SfcHomeNetwork.send(target,new SfcRepairNetwork.Cancel(repairKey(s,r,false,r.frame)));s.repairs.cancel();}
    private static void failRepair(State st,Session s,String reason){var r=s.repairs.active();if(r==null)return;finishRepair(s);int port=port(s,r.player);if(port>=0&&s.ports[port].id.equals(r.lease))release(s.connection.level().getServer(),s.ports[port],reason);}
    private static void repairTick(State st,Session s){var r=s.repairs.active();if(r==null)return;var server=s.connection.level().getServer();var target=server.getPlayerList().getPlayer(r.player);int port=port(s,r.player);
        if(!r.live(st.tick)||s.join!=null||target==null||port<0||!s.ports[port].id.equals(r.lease)||!endpointFacts(target,s.ports[port],s.connection)){failRepair(st,s,"本端重同步超时或控制权改变；主机继续");return;}
        try{
            for(int budget=0;budget<2&&r.phase==SfcRepairLedger.Phase.SEND;budget++){int offset=r.sent;byte[] part=r.peekPart();
                if(!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(target.connection.getConnection(),new SfcRepairNetwork.State(repairKey(s,r,false,r.frame),r.total(),offset,r.sha,part),part.length+2048,false))break;r.sentPart(part.length);}
            for(int budget=0;budget<2&&r.phase==SfcRepairLedger.Phase.REPLAY;budget++){
                var replay=s.repairs.replay(r.replay);
                if(replay.p1().length==0){if(cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(target.connection.getConnection(),new SfcRepairNetwork.Resume(repairKey(s,r,false,r.replay)),2048,false)){r.resume=r.replay;r.phase=SfcRepairLedger.Phase.DONE;}break;}
                if(!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(target.connection.getConnection(),new SfcRepairNetwork.Replay(repairKey(s,r,false,replay.first()),replay.p1(),replay.p2()),4096,false))break;r.replay+=replay.p1().length;
            }
        }catch(RuntimeException failure){failRepair(st,s,"本端追帧历史已过期；主机继续");}
    }
    private static void tick(MinecraftServer server){State st=state(server);st.tick++;
        for(var entry:List.copyOf(st.stopping.entrySet()))if(entry.getValue().hosted.terminated()){
            Session ending=entry.getValue();st.stopping.remove(entry.getKey());String error=ending.hosted.error();
            if(error!=null){var owner=server.getPlayerList().getPlayer(ending.host.player);if(owner!=null&&owner.connection.getConnection()==ending.host.connection)feedback(owner,error);}
        }
        if(st.tick%200==0)st.interactions.expireBefore(st.tick-2);for(Lease l:List.copyOf(st.leases.values())){ServerPlayer p=server.getPlayerList().getPlayer(l.player);if(p==null||!validLease(p,l,false))release(server,l,p!=null&&p.serverLevel()==l.console.getLevel()&&!cableReach(p,l.console)?"手柄线超过 6 格，已自动归还；主机继续运行":"手柄连接已失效，已自动归还");}
        for(Session s:List.copyOf(st.sessions.values())){
            boolean valid=hostValid(server.getPlayerList().getPlayer(s.host.player),s);
for(Lease l:s.ports.clone())if(l!=null){ServerPlayer p=server.getPlayerList().getPlayer(l.player);boolean allowed=p!=null&&endpointFacts(p,l,s.connection)&&(st.tick%20!=0||authorizedStart(p,l,s.connection));if(!allowed)release(server,l,"手柄或交互权限改变，已退出控制");if(allowed&&!held(p,l)){s.inputs[l.port].clear();if(s.hosted!=null)s.hosted.releasePort(l.port);}}
            if(st.sessions.get(s.connection.consoleId())!=s)continue;
            if(!valid){stop(st,s.connection.consoleId(),"运行宿主、主机或交互权限已改变");continue;}
            if(NETPLAY.containsKey(s)){
                var run=NETPLAY.get(s);if(run.room().closed()){stop(st,s.connection.consoleId(),"Netplay 主持连接已结束");continue;}
                renewNetplay(s);
                if(s.join!=null&&!joinValid(server,s,true))abortJoin(st,s,"加入授权已过期，请重新领取手柄");
                if(s.clock==null){if(st.tick-s.created>1200)stop(st,s.connection.consoleId(),"Netplay 启动超时");continue;}
                for(var l:s.ports.clone())if(l!=null&&s.health.expiredPort(l.port,st.tick))release(server,l,"手柄连接超时");
                continue;
            }
            if(s.hosted!=null){
                if(s.hosted.error()!=null){stop(st,s.connection.consoleId(),s.hosted.error());continue;}
                startClockIfReady(server,s);
            }
            repairTick(st,s);
            if(s.join!=null){
                if(!joinValid(server,s,true))abortJoin(st,s,"加入超时、卡带或交互权限改变；主机原局继续");
                else if(s.join.gate.phase()==SfcJoinGate.Phase.APPLYING){
                    var j=s.join;byte[] bytes=j.gate.bytes();
                    for(int budget=0;budget<2&&bytes!=null&&j.sent<bytes.length;budget++){int from=j.sent,end=Math.min(bytes.length,from+SfcJoinGate.CHUNK);var target=server.getPlayerList().getPlayer(j.gate.applicant);
                        if(target==null||!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(target.connection.getConnection(),new SfcJoinNetwork.State(s.id,s.epoch,j.gate.token,j.gate.frame(),bytes.length,from,j.gate.digest(),Arrays.copyOfRange(bytes,from,end)),end-from+2048,false))break;j.sent=end;}
                }
                if(s.join!=null&&paused(s)){s.inputs[0].clear();s.inputs[1].clear();continue;}
            }
            if(st.sessions.get(s.connection.consoleId())!=s)continue;
            if(s.clock==null){
                if(st.tick-s.created>1200)stop(st,s.connection.consoleId(),"运行端加载超时，主机已停止");
                continue;
            }
            for(Lease l:s.ports.clone())if(l!=null&&s.health.expiredPort(l.port,st.tick))release(server,l,"手柄输入超时，已退出控制；主机继续");
            for(Lease l:s.ports)if(l!=null&&s.health.neutralizeStalePort(l.port,st.tick)){
                s.inputs[l.port].neutralizeStale();
                if(s.hosted!=null)s.hosted.releasePort(l.port);
            }
            if(s.hosted!=null){
                s.hosted.inputs(s.inputs[0].next(),s.inputs[1].next());
                for(int budget=0;budget<8;budget++){var batch=s.hosted.poll();if(batch==null)break;
                    var targets=recipients(server,s);int kind=batch.getFirst().kind();int start=targets.isEmpty()?0:Math.floorMod(s.mediaRecipient[kind]++,targets.size());
                    for(int target=0;target<targets.size();target++){
                        var recipient=targets.get((start+target)%targets.size());
                        boolean owner=host(recipient,s);int port=port(s,recipient.getUUID());
                        if(!owner&&(port<0||!endpointFacts(recipient,s.ports[port],s.connection)))continue;
                        int bytes=batch.stream().mapToInt(part->part.data().length+384).sum();
                        if(!s.hosted.reserveMedia(server,bytes))continue;
                        cn.piq.sfchome.net.SfcHostedNetwork.send(recipient.connection.getConnection(),s.id,s.epoch,owner?s.host.id:s.ports[port].id,batch);
                    }
                    cn.piq.fcarcade.cabinet.WatchService.relay(server,s.watchSource,s.host.id,batch);
                }
                continue;
            }
            int n=s.clock.tick();
            if(s.frame>Integer.MAX_VALUE-4){stop(st,s.connection.consoleId(),"本局时长达到上限");continue;}
            int[]a=new int[n],b=new int[n];
            for(int i=0;i<n;i++){a[i]=s.inputs[0].next();b[i]=s.inputs[1].next();}
            var packet=new SfcHomeNetwork.Frames(s.id,s.epoch,s.frame,a,b);if(!s.playerMedia())s.repairs.append(s.frame,a,b);s.frame+=n;
            for(var player:recipients(server,s)){if(s.playerMedia()&&!host(player,s))continue;var repair=s.repairs.active();if(repair!=null&&repair.player.equals(player.getUUID())&&repair.phase!=SfcRepairLedger.Phase.DONE)continue;SfcHomeNetwork.send(player,packet);}
        }
        SfcCartridgeEditorService.tick(server);
        SfcLocalWatchServer.tick(server);
    }
    private static void stop(State st,UUID console,String reason){Session s=st.sessions.remove(console);if(s==null)return;retireNetplay(s);if(s.hosted!=null){st.stopping.put(console,s);s.hosted.close();}finishRepair(s);abortJoin(st,s,reason);for(var input:s.inputs)input.clear();MinecraftServer server=s.connection.level().getServer();for(var p:recipients(server,s))SfcHomeNetwork.send(p,new SfcHomeNetwork.Stopped(s.id,s.epoch,reason));HomeApplianceService.refresh(s.connection.level(),s.connection.television().getBlockPos());}
    private static void detach(State st,Session s,Lease lease,String reason){
        if(s.ports[lease.port]!=lease)return;MinecraftServer server=s.connection.level().getServer();
        var netplay=NETPLAY.get(s);if(netplay!=null&&!s.host.player.equals(lease.player))netplay.room().revoke(lease.connection);
        if(s.repairs.isolated(lease.player))finishRepair(s);s.repairs.forget(lease.player);
        s.inputs[lease.port]=new SfcInputTimeline();s.ports[lease.port]=null;s.ready[lease.port]=null;
        if(s.hosted!=null)s.hosted.releasePort(lease.port);
        ServerPlayer departed=server.getPlayerList().getPlayer(lease.player);if(departed!=null){SfcHomeNetwork.send(departed,new SfcHomeNetwork.Control(s.id,s.epoch,lease.id,lease.port,false));if(!s.host.player.equals(lease.player))SfcHomeNetwork.send(departed,new SfcHomeNetwork.Stopped(s.id,s.epoch,reason));}
        startClockIfReady(server,s);
    }
    private static void release(MinecraftServer server,Lease l,String reason){State st=state(server);if(st.leases.get(l.id)!=l)return;Session current=st.sessions.get(l.console.hardwareId());if(current!=null&&current.connection.console()==l.console){if(current.join!=null&&current.join.second==l)abortJoin(st,current,reason);detach(st,current,l,reason);}st.leases.remove(l.id);l.console.setControllerLeased(l.port,false);ServerPlayer p=server.getPlayerList().getPlayer(l.player);if(p!=null){removeControllerCopies(p,l.id);feedback(p,reason);}l.stack.setCount(0);}
    private static void releaseConsole(MinecraftServer server,SfcHomeConsoleBlockEntity c,String reason){State st=state(server);Session current=st.sessions.get(c.hardwareId());if(current!=null&&current.connection.console()==c)stop(st,c.hardwareId(),reason);for(Lease l:List.copyOf(st.leases.values()))if(l.console==c)release(server,l,reason);}
    private static final class State{long nextSession,tick;UUID transfer;final SfcHomeStartPolicy.InteractionGate interactions=new SfcHomeStartPolicy.InteractionGate();final Map<UUID,Lease>leases=new HashMap<>();final Map<UUID,Session>sessions=new HashMap<>(),stopping=new HashMap<>();}
    private static final class Lease{final UUID id,player;final net.minecraft.network.Connection connection;final SfcHomeConsoleBlockEntity console;final int port;ItemStack stack;final SfcControllerAuthority authority;Lease(UUID id,ServerPlayer p,SfcHomeConsoleBlockEntity c,int port,ItemStack s){this.id=id;player=p.getUUID();connection=p.connection.getConnection();console=c;this.port=port;stack=s;authority=new SfcControllerAuthority(id,player,port);}}
    private static final class Host{final UUID id=UUID.randomUUID(),player;final net.minecraft.network.Connection connection;Host(ServerPlayer p){player=p.getUUID();connection=p.connection.getConnection();}}
private static final class Session{final UUID watchSource=UUID.randomUUID();final long id,created;final int epoch;final HomeSystems.Connection connection;final String rom;final Host host;final Lease[] ports;final cn.piq.fcarcade.cabinet.CabinetSyncMode mode;final SfcInputTimeline[]inputs={new SfcInputTimeline(),new SfcInputTimeline()};final SfcInputHealth health=new SfcInputHealth();final SfcRepairLedger repairs=new SfcRepairLedger();final SfcHomeNetwork.Ready[]ready=new SfcHomeNetwork.Ready[2];SfcHomeNetwork.Ready hostReady;SfcHostedWorker hosted;int frame;final int[] mediaRecipient=new int[2];boolean multiplayer;Joining join;SfcFrameClock clock;Session(long id,int epoch,HomeSystems.Connection c,String rom,Host host,Lease[] ports,long tick,cn.piq.fcarcade.cabinet.CabinetSyncMode mode){this.id=id;this.epoch=epoch;connection=c;this.rom=rom;this.host=host;this.ports=ports;this.mode=Objects.requireNonNull(mode);created=tick;health.start(tick);}boolean playerMedia(){return mode==cn.piq.fcarcade.cabinet.CabinetSyncMode.MEDIA;}}
    private static final class Joining{final SfcJoinGate gate;final ItemStack card;final int port;Lease second;int sent;SfcHomeNetwork.Ready ready;Joining(SfcJoinGate gate,ItemStack card,int port){this.gate=gate;this.card=card.copy();this.port=port;}}
}
