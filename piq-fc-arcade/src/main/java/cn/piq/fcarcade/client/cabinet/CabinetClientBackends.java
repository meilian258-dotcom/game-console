package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.retro.api.RetroEmulator;
import cn.piq.retro.api.RetroEmulatorFactory;
import cn.piq.retro.api.RetroFactoryRegistry;
import cn.piq.retro.api.RetroFrame;
import cn.piq.retro.client.GamepadInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Client host owns input/display/lifetime; optional addons own their emulation cores. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class CabinetClientBackends implements CabinetNetwork.ClientSink, CabinetRoomNetwork.ClientSink, CabinetSyncNetwork.ClientSink {
    private static final Map<ResourceLocation,CabinetBackend> BACKENDS=new LinkedHashMap<>();
    private static final RetroFactoryRegistry FACTORIES=new RetroFactoryRegistry();
    private static final CabinetConfigureIntent<CabinetTarget> CONFIGURE=new CabinetConfigureIntent<>();
    private static final ExecutorService STARTER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"PIQ-Cabinet-Open");t.setDaemon(true);return t;});
    private static final AtomicBoolean OPENING=new AtomicBoolean();
    private static final AtomicReference<RetroEmulator> PENDING=new AtomicReference<>();
    private static CabinetNetwork.Launch launch;
    private static CabinetRoomNetwork.Assignment room;
    private static CabinetRoomNetwork.NetplayStart netplayGrant;
    private static CabinetPeerInputs peers;
    private static CabinetMediaStream media;
    private static CabinetSyncClient sync;
    private static final CabinetAssignmentHistory HISTORY=new CabinetAssignmentHistory();
    private static long inputSequence;
    private static long coinSequence;
    private static CabinetBackend backend;
    private static CabinetRomBindings.Key selectionKey;
    private static Object sessionConnection;
    private static boolean configureSelection;
    private static RetroEmulator emulator;
    private static CabinetAudio audio;
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static float aspect=4F/3F;
    private static int rotation,ticks;
    private static boolean playing,announced;
    private static boolean moderator;
    private static int watchers;
    private static long watchExpires;
    private static final CabinetImmersiveInput INPUT=new CabinetImmersiveInput();
    private static final Object INPUT_OWNER=new Object();
    private static int mixedInputMask;
    private static final PgmStartSequence PGM_START=new PgmStartSequence();
    private static volatile int generation;
    private static volatile boolean shuttingDown;
    private CabinetClientBackends(){}

    public static synchronized void register(ResourceLocation id,CabinetBackend provider){
        Objects.requireNonNull(id);Objects.requireNonNull(provider);
        if(id.equals(CabinetBackends.NES)||id.toString().length()>128||BACKENDS.size()>=16||BACKENDS.containsKey(id))
            throw new IllegalArgumentException("Duplicate/reserved/invalid cabinet backend: "+id);
        FACTORIES.register(id.toString(),rom->Objects.requireNonNull(provider.open(rom),"Core factory returned null").asRetro());
        BACKENDS.put(id,provider);
    }
    static synchronized CabinetBackend find(ResourceLocation id){return BACKENDS.get(id);}
    /** Capture the same trusted backend and shared-game path as players, with a watch-only lease. */
    public static cn.piq.fcarcade.client.watch.NetplayWatchContent.Preparation prepareObservation(WatchNetwork.NetplayStart start,Connection connection)throws Exception{
        var d=start.watch().descriptor();var mc=Minecraft.getInstance();
        if(!d.provider().equals(CabinetRooms.WATCH_PROVIDER))throw new IllegalArgumentException("Observer provider");
        var target=CabinetTarget.resolve(mc.level,d.origin().pos());
        if(target==null||!target.identity().equals(d.origin().identity()))throw new IllegalStateException("旁观机柜已改变");
        var provider=find(start.backend());if(provider==null)throw new IllegalStateException("未安装此街机核心附属");
        String problem=provider.netplayUnavailableReason();if(problem!=null)throw new IllegalStateException(problem);
        var factory=provider.prepareNetplayFactory();
        var key=CabinetGameSelection.key(target.dimension(),target.identity(),start.backend()).orElseThrow();
        var request=new CabinetNetwork.Launch(target,start.backend(),start.watch().lease());
        return new cn.piq.fcarcade.client.watch.NetplayWatchContent.Preparation(()->{
            Path path=CabinetSharedGames.resolve(request,null,false,provider,key,connection);
            if(!start.romHash().equals(CabinetSharedGames.resolvedHash(request.lease())))throw new IllegalStateException("旁观游戏已改变");
            return factory.open(path);
        },()->CabinetSharedGames.cancel(request.lease()));
    }
    static boolean configure(CabinetNetwork.Menu menu,ResourceLocation selected){
        var mc=Minecraft.getInstance();
        CONFIGURE.clear();
        if(shuttingDown||launch!=null||mc.getConnection()==null||!menu.target().matches(mc.level)
                ||menu.entries().stream().noneMatch(entry->entry.id().equals(selected)))return false;
        if(selected.equals(CabinetBackends.NES))return true; // Preserve the original NES library/interaction route.
        return CONFIGURE.arm(mc.getConnection(),menu.target(),selected.toString(),menu.token(),System.nanoTime(),TimeUnit.SECONDS.toNanos(5));
    }
    static void cancelConfigure(){CONFIGURE.clear();}
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{var sink=new CabinetClientBackends();CabinetNetwork.setClientSink(sink);CabinetRoomNetwork.setClientSink(sink);CabinetSyncNetwork.setClientSink(sink);CabinetJoinNetwork.setClientSink(new CabinetJoinClient());
            CabinetGameNetwork.setLibrarySink(CabinetSetupScreen::receive);
            cn.piq.fcarcade.client.NetworkDiagnosticsClient.registerDevices("shared-cabinet",CabinetClientBackends::diagnosticDevices);});}
    }
    @Override public boolean acceptsConnection(Connection source){var connection=Minecraft.getInstance().getConnection();return connection!=null&&connection.getConnection()==source;}
    private static boolean guest(){return room!=null&&room.port()!=0;}
    static boolean directFcJoin(UUID id){return room!=null&&room.room().equals(id)&&room.backend().equals(CabinetBackends.NES);}
    private static boolean synchronous(){return room!=null&&room.mode()==CabinetSyncMode.LOCAL_SYNC;}
    private static boolean netplay(){return netplayGrant!=null&&room==netplayGrant.assignment();}
    private static boolean controlEnabled=true;
    private static boolean paidNetplay(){return netplay()&&room.coinRequired();}
    private static boolean serverNetplayInput(){return netplay()&&(room.coinRequired()||PgmServicePolicy.supportsBackend(room.backend().toString()));}
    @Override public void control(CabinetRoomNetwork.Control value){
        if(room==null||!room.room().equals(value.room())||!room.member().equals(value.member())||!current())return;
        controlEnabled=value.enabled();PGM_START.cancel();releaseKeyboard();CabinetUseGuard.suppressWhileHeld();
        if(controlEnabled)CabinetClientOwner.acquire(INPUT_OWNER);else CabinetClientOwner.release(INPUT_OWNER);
    }
    @Override public void netplay(CabinetRoomNetwork.NetplayStart value){
        cn.piq.fcarcade.client.watch.WatchClient.controlStarting();
        if(room!=null&&room.member().equals(value.assignment().member())&&Minecraft.getInstance().getConnection()==sessionConnection)return;
        if(shuttingDown||launch!=null||room!=null||OPENING.get()){release(value.assignment().member());return;}
        netplayGrant=value;assignment(value.assignment());if(room!=value.assignment())netplayGrant=null;
    }
    private static boolean hosted(){return room!=null&&room.mode()==CabinetSyncMode.SERVER_MEDIA;}
    private static boolean receiver(){return hosted()||guest()&&!synchronous();}
    @Override public void moderator(CabinetRoomNetwork.Moderator value){if(room!=null&&hosted()&&room.room().equals(value.room())&&room.member().equals(value.member())&&Minecraft.getInstance().getConnection()==sessionConnection)moderator=value.enabled();}
    @Override public void pgmService(CabinetRoomNetwork.PgmService value){
        if(!netplay()||room.port()!=0||!room.room().equals(value.room())||!room.member().equals(value.member())||!current()||!running())return;
        if(PGM_START.arm(System.nanoTime()))notice("准备发送维护组合键；先关闭聊天、松开所有按键。出现菜单后按游戏提示调整，菜单 EXIT 返回；未出现说明本游戏不支持此入口。");
    }
    @Override public void frames(CabinetSyncNetwork.Frames p){if(sync!=null)sync.frames(p);}
    @Override public void restore(CabinetSyncNetwork.Restore p){if(sync!=null)try{if(sync.restore(p)){if(audio!=null)audio.reset();mixedInputMask=0;GamepadInput.pause(INPUT_OWNER);cn.piq.retro.client.KeyboardInput.pause(INPUT_OWNER);}}catch(RuntimeException failure){stop("本地同步状态无效："+failure.getMessage(),true,failure);}}
    @Override public void active(CabinetSyncNetwork.Active p){if(sync!=null)sync.active(p);}
    @Override public void uploadGrant(CabinetSyncNetwork.UploadGrant p){if(sync!=null)sync.grant(p);}
    private static boolean roomMessage(UUID id,UUID host){return room!=null&&room.room().equals(id)&&room.hostMember().equals(host)&&Minecraft.getInstance().getConnection()==sessionConnection;}
    @Override public void assignment(CabinetRoomNetwork.Assignment request){
        var mc=Minecraft.getInstance();
        if(room!=null&&room.member().equals(request.member())&&mc.getConnection()==sessionConnection)return;
        if(!HISTORY.accept(mc.getConnection(),request.member())){notice("已忽略过期街机席位；若频繁切换请重新连接服务器");return;}
        var selected=find(request.backend());
        if(shuttingDown||launch!=null||room!=null||OPENING.get()||mc.player==null||mc.getConnection()==null
                ||!request.target().matches(mc.level)||CabinetUseGuard.blocked()||cn.piq.fcarcade.client.ClientArcadeEvents.isControlling()
                ||!CabinetClientAdmission.accepts(request,netplayGrant)
                ||(mc.screen!=null&&!(mc.screen instanceof CabinetMenuScreen))){release(request.member());notice("街机席位尚未就绪或已有游戏占用");return;}
        room=request;peers=new CabinetPeerInputs();inputSequence=0;coinSequence=0;ticks=0;sessionConnection=mc.getConnection();controlEnabled=true;
        moderator=false;
        if(request.port()==0&&(!hosted()||request.member().equals(request.hostMember())))return; // Initial author prepares shared data; hosted P1 rejoins are receivers.
        if(!CabinetClientOwner.acquire(INPUT_OWNER)){room=null;peers=null;release(request.member());return;}
        try{
            launch=new CabinetNetwork.Launch(request.target(),request.backend(),request.member());backend=selected;
            if(synchronous()){
                if(selected==null||(!netplay()&&!CabinetBackends.supportsSync(request.backend())))throw new IllegalStateException("客户端未安装可同步的核心适配器");
                selectionKey=CabinetGameSelection.key(request.primary().dimension(),request.primary().identity(),request.backend()).orElseThrow();configureSelection=false;
                startGame(null,false);return;
            }
            selectionKey=null;configureSelection=false;playing=true;announced=true;ticks=0;generation++;INPUT.reset();
            audio=new CabinetAudio();media=new CabinetMediaStream(request.room(),request.hostMember(),false,hosted());
            mc.setScreen(null);
        }catch(RuntimeException|LinkageError failure){stop("街机接收器启动失败",true,failure);}
    }
    @Override public void seat(CabinetRoomNetwork.Seat message){
        if(!roomMessage(message.room(),message.hostMember())||guest()||peers==null||message.port()>=room.capacity())return;
        if(peers.seat(message.member(),message.port(),message.joined())&&emulator!=null){
            try{emulator.releasePort(message.port());}catch(RuntimeException|LinkageError failure){stop("模拟器不能独立释放玩家按键",true,failure);return;}
        }
        updateMediaDemand();
    }
    /** Spectators affect media demand only; never seat assignment, input or core ownership. */
    public static void watchDemand(WatchNetwork.HostDemand value){
        var d=value.descriptor();
        if(room==null||guest()||!roomMessage(d.source(),d.hostLease())||!current()
                ||!d.provider().equals(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","cabinet"))
                ||!d.dimension().equals(room.primary().dimension())
                ||!d.origin().pos().equals(room.primary().anchor())||!d.origin().identity().equals(room.primary().identity()))return;
        watchers=value.watchers();watchExpires=System.nanoTime()+6_000_000_000L;updateMediaDemand();
    }
    public static boolean hasLocalSession(){return launch!=null||room!=null||OPENING.get();}
    private static List<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device> diagnosticDevices(){
        var mc=Minecraft.getInstance();
        if(!playing||!current()||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected())return List.of();
        var mode=netplay()?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.NETPLAY:room==null?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.LOCAL_ONLY:switch(room.mode()){
            case LOCAL_SYNC->cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.LOCAL_INPUT;
            case SERVER_MEDIA->cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.SERVER_MEDIA;
            case MEDIA->cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.PLAYER_MEDIA;
        };
        var pos=launch.target().anchor();
        return List.of(new cn.piq.fcarcade.client.NetworkDiagnosticsView.Device("共享街机 @ "+pos.toShortString(),mode,
                cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.CONTROLLING,mc.player.distanceToSqr(pos.getCenter())));
    }
    /** Read-only prompt authorization; consent never creates a seat on the client. */
    static boolean ownsRoom(UUID roomId,UUID hostMember,Object connection){
        var live=Minecraft.getInstance().getConnection();
        return room!=null&&room.capacity()>1&&(hosted()?moderator&&room.member().equals(hostMember):room.port()==0&&room.hostMember().equals(hostMember))&&room.room().equals(roomId)
                &&sessionConnection==connection&&live==connection&&live!=null&&live.getConnection().isConnected()&&playing&&current();
    }
    private static boolean needsMedia(){return peers!=null&&(!synchronous()&&peers.guests()>0||(watchers>0&&System.nanoTime()<watchExpires));}
    private static void updateMediaDemand(){if(media!=null&&!guest()&&!hosted())media.sending(needsMedia());}
    @Override public void buttons(CabinetRoomNetwork.Buttons message){
        if(synchronous()&&!serverNetplayInput())return;
        if(!roomMessage(message.room(),message.hostMember())||guest()||peers==null||emulator==null||message.port()>=room.capacity())return;
        if(!peers.input(message.member(),message.port(),message.seq(),message.mask()))return;
        try{if(message.reset())releaseGameplayPort(message.port());else if(serverNetplayInput()&&emulator instanceof CabinetNetplayEmulator e)e.serverInput(message.port(),message.mask());else emulator.offerInputs(peers.mask(0),peers.mask(1),peers.mask(2),peers.mask(3));}
        catch(RuntimeException|LinkageError failure){stop("模拟器多人输入异常",true,failure);}
    }
    @Override public void coin(CabinetRoomNetwork.Coin message){
        if(!roomMessage(message.room(),message.hostMember())||room.mode()!=CabinetSyncMode.MEDIA&&!paidNetplay()||!room.coinRequired()
                ||guest()||!current()||!playing||emulator==null||!emulator.isReady()||emulator.error()!=null||!emulator.supportsCoinPreservingRelease()||peers==null
                ||message.sequence()<=coinSequence||!peers.owns(message.member(),message.port()))return;
        coinSequence=message.sequence();
        try{if(paidNetplay()&&emulator instanceof CabinetNetplayEmulator e){e.coin(message.port(),message.sequence());return;}
            for(int[] masks:CabinetCoinPolicy.pulse(new int[]{peers.mask(0),peers.mask(1),peers.mask(2),peers.mask(3)},message.port()))
            emulator.offerInputs(masks[0],masks[1],masks[2],masks[3]);}
        catch(RuntimeException|LinkageError failure){stop("投币输入异常，街机已停止",true,failure);}
    }
    @Override public void media(CabinetRoomNetwork.Stream stream){
        var message=stream.media();
        if(roomMessage(message.room(),message.hostMember())&&room.member().equals(stream.member())&&receiver()&&media!=null)
            media.accept(new CabinetMediaPacket(message.room(),message.hostMember(),message.sequence(),message.kind(),message.index(),message.count(),message.width(),message.height(),message.aspect(),message.rotation(),message.rawLength(),message.data()));
    }
    @Override public void openMenu(CabinetNetwork.Menu menu){
        var mc=Minecraft.getInstance();
        if(shuttingDown||!menu.target().matches(mc.level)||mc.player==null)return;
        if(CabinetUseGuard.blocked())return;
        if(launch!=null){
            if(room!=null&&(menu.target().equals(room.primary())||menu.target().equals(room.secondary())))mc.setScreen(new CabinetSyncSettingsScreen(null,menu.target(),menu.selected(),menu.debugTool()?menu.token():null));
            else notice("请先退出当前街机");return;
        }
        if(menu.debugTool()){
            // This is a settings-only reply, not a backend selection or a launch.
            // Never cover a dialog the player opened while this reply was in flight.
            if(mc.screen!=null||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected())return;
            CONFIGURE.clear();mc.setScreen(new CabinetSyncSettingsScreen(null,menu.target(),menu.selected(),menu.token()));return;
        }
        if(mc.screen==null||mc.screen instanceof CabinetMenuScreen){CONFIGURE.clear();mc.setScreen(new CabinetMenuScreen(menu));}
    }
    @Override public void openBackend(CabinetNetwork.Launch request){
        var mc=Minecraft.getInstance();var selected=find(request.backend());
        if(launch!=null&&launch.lease().equals(request.lease())&&mc.getConnection()==sessionConnection)return;
        boolean network=CabinetBackends.maxPlayers(request.backend())>0;
        if(network&&(room==null||!room.member().equals(request.lease())||room.port()!=0||!room.target().equals(request.target())||!room.backend().equals(request.backend()))){release(request.lease());return;}
        if(!network&&!HISTORY.accept(mc.getConnection(),request.lease()))return;
        boolean configuring=CONFIGURE.consume(mc.getConnection(),request.target(),request.backend().toString(),System.nanoTime());
        var selectedKey=CabinetGameSelection.key(request.target().dimension(),request.target().identity(),request.backend());
        String error=selected==null?"客户端没有安装对应的模拟器附属":hosted()?null:netplay()?selected.netplayUnavailableReason():synchronous()?selected.syncUnavailableReason():selected.unavailableReason();
        if(CabinetUseGuard.blocked()){release(request.lease());return;}
        var metadata=CabinetBackends.find(request.backend());
        if(error==null&&metadata==null)error="客户端与服务器的模拟器声明不一致";
        if(error==null&&selectedKey.isEmpty())error="当前世界或连接尚未就绪，无法读取机柜游戏配置";
        if(error==null&&cn.piq.fcarcade.client.ClientArcadeEvents.isControlling())error="请先退出正在控制的 FC 游戏";
        if(error==null&&metadata.localOnly()&&!localWorld())error="此模拟器目前仅支持未开放局域网的本机世界";
        if(error==null&&(shuttingDown||launch!=null||OPENING.get()||!request.target().matches(mc.level)||mc.player==null
                ||(mc.screen!=null&&!(mc.screen instanceof CabinetMenuScreen))))error="机柜尚未同步或已有界面占用，请稍后重试";
        if(error==null&&!CabinetClientOwner.acquire(INPUT_OWNER))error="请先退出正在使用的其他街机";
        if(error!=null){release(request.lease());if(room!=null&&room.member().equals(request.lease()))stop(null,false);notice(error);return;}
        launch=request;backend=selected;selectionKey=selectedKey.orElseThrow();sessionConnection=mc.getConnection();configureSelection=configuring;ticks=0;
        if(configuring)mc.setScreen(new CabinetSetupScreen(selected,request,hosted()?"服务器托管":synchronous()?"本地输入同步":"玩家音画串流"));
        else startGame(null,false);
    }
    @Override public void closed(CabinetNetwork.Closed message){if((launch!=null&&launch.lease().equals(message.lease()))||(room!=null&&room.member().equals(message.lease()))){if(playing)CabinetUseGuard.suppressWhileHeld();stop(message.reason(),false);}}
    private static boolean localWorld(){var server=Minecraft.getInstance().getSingleplayerServer();return server!=null&&!server.isPublished();}
    static boolean current(){
        var mc=Minecraft.getInstance();if(launch==null||mc.getConnection()!=sessionConnection||mc.player==null||!mc.player.isAlive()||mc.player.isSpectator()||!launch.target().matches(mc.level)||cn.piq.fcarcade.client.ClientArcadeEvents.isControlling())return false;
        var meta=CabinetBackends.find(launch.backend());
        var pos=launch.target().anchor();return !shuttingDown&&meta!=null&&(!meta.localOnly()||localWorld())
                &&(backgroundHost()||mc.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<=64);
    }
    private static boolean backgroundHost(){return playing&&announced&&room!=null&&room.port()==0&&CabinetCoinPolicy.supported(room.backend().toString());}
    static boolean running(){return netplay()?emulator!=null&&emulator.isReady():synchronous()?sync!=null&&sync.active()&&emulator!=null&&emulator.isReady():receiver()?media!=null:emulator!=null&&emulator.isReady();}
    /** Visual state only: this accessor never sends inputs, assigns seats or changes the core. */
    public static int[] visualInputs(CabinetTarget target){
        if(target==null||!playing||!current()||!target.matches(Minecraft.getInstance().level))return new int[2];
        Minecraft mc=Minecraft.getInstance();
        int localVisual=mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()?mixedInputMask:0;
        if(room==null)return launch.target().equals(target)?new int[]{localVisual,0}:new int[2];
        int first=room.primary().equals(target)?0:target.equals(room.secondary())?CabinetSeats.physical(room.primary().dual()):-1;
        int count=room.secondary()==null?room.capacity():CabinetSeats.physical(target.dual());
        return peers==null||first<0?new int[2]:peers.visualPair(first,count,room.port(),localVisual,!guest());
    }
    static void start(Path rom){
        if(!configureSelection||!(Minecraft.getInstance().screen instanceof CabinetSetupScreen))return;
        startGame(rom,true);
    }
    static void startConfiguredServer(CabinetNetwork.Launch expected){
        if(launch!=expected||!configureSelection||!(Minecraft.getInstance().screen instanceof CabinetSetupScreen)||!current())return;
        startGame(null,false);
    }
    private static void startGame(Path chosen,boolean remember){
        if(!current()||emulator!=null||!OPENING.compareAndSet(false,true))return;
        if(hosted()){startHosted(chosen,remember);return;}
        if(netplay()){startNetplay(chosen,remember);return;}
        if(synchronous()){startSynchronous(chosen,remember);return;}
        var mc=Minecraft.getInstance();var registered=FACTORIES.find(launch.backend().toString());
        if(registered==null){OPENING.set(false);stop("客户端未注册模拟器工厂",true);return;}
        var provider=backend;var key=selectionKey;
        var preparedLaunch=launch;var preparedConnection=sessionConnection;int preparedGeneration=generation;
        final RetroEmulatorFactory factory;
        try{factory=Objects.requireNonNull(provider.prepareFactory(registered,key,mc.player.getUUID(),mc.getConnection().getConnection()),"Missing prepared factory");}
        catch(Exception|LinkageError failure){
            OPENING.set(false);
            if(launch==preparedLaunch&&sessionConnection==preparedConnection&&generation==preparedGeneration)
                stop("模拟器会话准备失败："+failure.getMessage(),true,failure);
            return;
        }
        // An addon callback can synchronously close a screen/session. Never resurrect its old launch.
        if(launch!=preparedLaunch||sessionConnection!=preparedConnection||generation!=preparedGeneration
                ||backend!=provider||selectionKey!=key||!current()){
            OPENING.set(false);
            if(launch==preparedLaunch&&sessionConnection==preparedConnection&&generation==preparedGeneration)
                stop("模拟器启动上下文已失效",true);
            return;
        }
        int token=++generation;
        configureSelection=false;playing=true;announced=false;INPUT.reset();mc.setScreen(null);
        try{STARTER.execute(()->{RetroEmulator opened=null;
            try{
                if(shuttingDown||token!=generation)return;
                Path rom=CabinetSharedGames.resolve(preparedLaunch,chosen,remember,provider,key,((net.minecraft.client.multiplayer.ClientPacketListener)preparedConnection).getConnection());
                CabinetGameSelection.validate(rom,provider.romExtensions(),provider.romExcludedNames());
                if(shuttingDown||token!=generation)return;
                if(remember)CabinetGameSelection.remember(key,rom);
                opened=Objects.requireNonNull(factory.open(rom),"Core factory returned null");var ready=opened;
                if(shuttingDown||token!=generation){CabinetCleanup.closeRetro(ready);return;}
                PENDING.set(ready);
                if(shuttingDown||token!=generation){PENDING.compareAndSet(ready,null);CabinetCleanup.closeRetro(ready);return;}
                mc.execute(()->{
                    PENDING.compareAndSet(ready,null);
                    if(token!=generation||!current()||!playing){CabinetCleanup.closeRetro(ready);return;}
                    try{
                        emulator=ready; // Own the core before capability checks which can throw.
                        if(room!=null&&ready.maxPlayers()<room.capacity())throw new IllegalStateException("核心端口数量与服务端声明不一致");
                        audio=new CabinetAudio();
                        if(room!=null){media=new CabinetMediaStream(room.room(),room.hostMember(),true);updateMediaDemand();}
                    }
                    catch(RuntimeException|LinkageError failure){stop("模拟器显示初始化失败："+failure.getMessage(),true,failure);}
                });
            }catch(Exception|LinkageError failure){
                PENDING.compareAndSet(opened,null);CabinetCleanup.closeRetro(opened);
                if(!shuttingDown)try{mc.execute(()->{if(token==generation)stop("模拟器启动失败："+failure.getMessage(),true,failure);});}
                catch(RejectedExecutionException ignored){/* The game executor is already shutting down. */}
            }
            finally{OPENING.set(false);}
        });}catch(RejectedExecutionException failure){OPENING.set(false);stop("模拟器启动线程已关闭，请重新启动客户端",true,failure);}
    }
    static void input(int p1,int p2){
        if(!controlEnabled){p1=0;p2=0;}
        if(netplay()){
            if(!controlEnabled)p1=0;
            if(current()){if(serverNetplayInput())CabinetRoomNetwork.send(new CabinetRoomNetwork.Input(room.room(),room.member(),inputSequence++,CabinetCoinPolicy.filter(room.coinRequired(),p1)));
                else if(emulator instanceof CabinetNetplayEmulator e)e.localInput(p1);}return;
        }
        if(room!=null){p1=CabinetCoinPolicy.filter(room.coinRequired(),p1);p2=CabinetCoinPolicy.filter(room.coinRequired(),p2);}
        if(room!=null){
            if(!current())return;
            if(synchronous()){if(sync!=null&&sync.active())CabinetRoomNetwork.send(new CabinetSyncNetwork.Input(room.room(),room.member(),1,inputSequence++,p1,false));return;}
            CabinetRoomNetwork.send(new CabinetRoomNetwork.Input(room.room(),room.member(),inputSequence++,p1));
            if(!guest()&&emulator!=null)try{peers.local(room.member(),0,p1);emulator.offerInputs(p1,peers.mask(1),peers.mask(2),peers.mask(3));}
            catch(RuntimeException|LinkageError failure){stop("模拟器输入异常："+failure.getMessage(),true,failure);}
        }else if(emulator!=null)try{emulator.offerInput(p1,p2);}catch(RuntimeException|LinkageError failure){stop("模拟器输入异常："+failure.getMessage(),true,failure);}
    }
    static void clearInput(){
        if(netplay()){if(serverNetplayInput()&&current())CabinetRoomNetwork.send(new CabinetRoomNetwork.Reset(room.room(),room.member(),inputSequence++));else if(emulator!=null)emulator.clearInput();return;}
        if(room!=null){
            if(synchronous()){if(current())CabinetRoomNetwork.send(new CabinetSyncNetwork.Input(room.room(),room.member(),1,inputSequence++,0,true));return;}
            if(current())CabinetRoomNetwork.send(new CabinetRoomNetwork.Reset(room.room(),room.member(),inputSequence++));
            if(!guest()&&emulator!=null)try{peers.local(room.member(),0,0);releaseGameplayPort(0);}catch(RuntimeException|LinkageError failure){stop("模拟器松键异常",true,failure);}
        }
        else if(emulator!=null)try{emulator.clearInput();}catch(RuntimeException|LinkageError failure){stop("模拟器松键异常："+failure.getMessage(),true,failure);}
    }
    private static void releaseGameplayPort(int port){
        if(room!=null&&room.coinRequired()&&emulator.supportsCoinPreservingRelease())emulator.releaseGameplayPortKeepingCoin(port);
        else emulator.releasePort(port); // Old cores never admitted a paid coin; freeplay is unchanged.
    }
    private static void syncInput(){
        var mc=Minecraft.getInstance();
        boolean active=controlEnabled&&playing&&current()&&running()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();
        var profile=launch!=null&&launch.backend().toString().equals("piq_sfc_home:sfc")?cn.piq.retro.client.KeyboardConfig.Profile.SFC:cn.piq.retro.client.KeyboardConfig.Profile.ARCADE;
        cn.piq.retro.client.KeyboardInput.attach(INPUT_OWNER,profile,
                () -> cn.piq.retro.client.KeyboardConfig.presetKeys(cn.piq.retro.client.KeyboardConfig.Profile.ARCADE,cn.piq.retro.client.KeyboardConfig.Preset.LEGACY).stream().map(k->new int[]{k}).toArray(int[][]::new),
                () -> playing&&current()&&running()&&cn.piq.retro.input.InputOwnership.owns(INPUT_OWNER)&&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected(),CabinetClientBackends::releaseKeyboard,CabinetClientBackends::syncInput);
        var keyboard=cn.piq.retro.client.KeyboardInput.poll(INPUT_OWNER,0,active);
        active=active&&keyboard.enabled()&&keyboard.armed();
        int mask=GamepadInput.mix(INPUT_OWNER,profile==cn.piq.retro.client.KeyboardConfig.Profile.SFC?GamepadInput.ProfileKind.SFC:GamepadInput.ProfileKind.ARCADE,keyboard.mask(),active);
        int service=PGM_START.poll(active,System.nanoTime());if(service>=0)mask=service;
        if(active&&mask!=mixedInputMask){mixedInputMask=mask;input(mask,0);}
        if(!active){GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;}
    }
    private static void releaseKeyboard(){INPUT.reset();GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;clearInput();}
    @SubscribeEvent public static void key(InputEvent.Key event){
        if(!playing)return;
        syncInput();
    }
    @SubscribeEvent public static void useKey(InputEvent.InteractionKeyMappingTriggered event){
        if(!event.isUseItem())return;
        if(CabinetUseGuard.inputBlocked()){event.setCanceled(true);event.setSwingHand(false);return;}
        var mc=Minecraft.getInstance();
        if(event.getHand()==net.minecraft.world.InteractionHand.MAIN_HAND&&mc.screen==null&&mc.getConnection()!=null
                &&CabinetPowerPicking.pick(mc.player)!=null){
            event.setCanceled(true);event.setSwingHand(false);CabinetUseGuard.suppressPowerRepeats();
            CabinetNetwork.send(new CabinetNetwork.PowerPress());
        }
    }
    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event){
        if(!playing||event.getNewScreen()==null)return;
        PGM_START.cancel();
        cn.piq.retro.client.KeyboardInput.pause(INPUT_OWNER);releaseKeyboard();
    }
    private static void stop(String reason,boolean notifyServer,Throwable failure){
        cn.piq.fcarcade.client.ui.DeviceNotices.record("街机",reason,failure);
        stop(null,notifyServer);
    }
    static void stop(String reason,boolean notifyServer){
        generation++;var previous=launch;var previousRoom=room;var previousConnection=sessionConnection;launch=null;backend=null;room=null;netplayGrant=null;peers=null;watchers=0;watchExpires=0;
        PGM_START.cancel();
        if(previous!=null)CabinetSharedGames.cancel(previous.lease());else if(previousRoom!=null)CabinetSharedGames.cancel(previousRoom.member());
        if(media!=null){media.close();media=null;}
        if(sync!=null){sync.close();sync=null;}
        selectionKey=null;sessionConnection=null;configureSelection=false;CONFIGURE.clear();
        playing=false;announced=false;moderator=false;INPUT.reset();
        GamepadInput.release(INPUT_OWNER);mixedInputMask=0;
        cn.piq.retro.client.KeyboardInput.release(INPUT_OWNER);
        CabinetClientOwner.release(INPUT_OWNER);
        var old=emulator;emulator=null;CabinetCleanup.closeRetro(old);
        var pending=PENDING.getAndSet(null);if(pending!=old)CabinetCleanup.closeRetro(pending);
        if(audio!=null){audio.close();audio=null;}dropTexture();
        var mc=Minecraft.getInstance();if(mc.screen instanceof CabinetSetupScreen||mc.screen instanceof CabinetPlayScreen)mc.setScreen(null);
        if(notifyServer&&mc.getConnection()==previousConnection){if(previous!=null)release(previous.lease());else if(previousRoom!=null)release(previousRoom.member());}if(reason!=null)notice(reason);
    }
    private static void release(UUID id){if(Minecraft.getInstance().getConnection()!=null)CabinetNetwork.send(new CabinetNetwork.Release(id));}
    static void notice(String text){cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("街机",text);}
    static String shortText(String text,int max){if(text==null)return "";String clean=text.replaceAll("[\\p{Cntrl}]"," ");return clean.length()>max?clean.substring(0,max)+"…":clean;}
    private static void dropTexture(){if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);else if(texture!=null)texture.close();texture=null;textureId=null;}
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){shuttingDown=true;stop(null,false);STARTER.shutdownNow();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        HISTORY.connection(Minecraft.getInstance().getConnection());
        CONFIGURE.expire(Minecraft.getInstance().getConnection(),System.nanoTime());
        CabinetUseGuard.blocked(); // Observe release even after the cabinet session has closed.
        if(launch==null){if(room!=null&&(Minecraft.getInstance().getConnection()!=sessionConnection||++ticks>100))stop(null,false);return;}var mc=Minecraft.getInstance();
        if(!current()||(!playing&&!(mc.screen instanceof CabinetSetupScreen))){stop("已退出模拟器",true);return;}
        if(backgroundHost()&&controlEnabled&&mc.player.distanceToSqr(launch.target().anchor().getCenter())>64){
            controlEnabled=false;PGM_START.cancel();releaseKeyboard();CabinetClientOwner.release(INPUT_OWNER);
        }
        if(sync!=null)try{sync.tick();}catch(RuntimeException|LinkageError failure){stop("本地同步失败："+failure.getMessage(),true,failure);return;}
        syncInput();
        if(launch==null)return;
        if(playing&&running()&&!announced){announced=true;if(room!=null&&(!synchronous()||netplay()))CabinetRoomNetwork.send(new CabinetRoomNetwork.Ready(room.room(),room.member(),emulator!=null&&emulator.supportsCoinPreservingRelease()));}
        if(++ticks%10==0)CabinetNetwork.send(new CabinetNetwork.Heartbeat(launch.lease()));
        if(room!=null&&playing&&running()&&ticks%5==0)input(mixedInputMask,0);
        if(launch==null)return;
        if(emulator!=null&&emulator.error()!=null){stop(emulator.error(),true);return;}
        if(media!=null&&media.error()!=null){stop(media.error(),true);return;}
        drainMedia();
        if(audio!=null){
            double distance=mc.player.distanceToSqr((room==null?launch.target():room.primary()).anchor().getCenter());
            if(room!=null&&room.secondary()!=null)distance=Math.min(distance,mc.player.distanceToSqr(room.secondary().anchor().getCenter()));
            float gain=(float)Math.max(0,1-Math.sqrt(distance)/CabinetClientSettings.rules().range());
            audio.gain(gain*.6F*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS));
        }
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||(emulator==null&&!receiver())||!current())return;
        syncInput();
        if((emulator==null&&!receiver())||launch==null)return;
        try{
            boolean visible=CabinetVideoDisplay.visible(event.getCamera().getPosition(),room==null?launch.target():room.primary())
                ||room!=null&&CabinetVideoDisplay.visible(event.getCamera().getPosition(),room.secondary());
            RetroFrame frame=receiver()&&media!=null?media.pollVideo():emulator==null?null:emulator.pollFrame();if(frame!=null){
                updateMediaDemand();
                if(!guest()&&!hosted()&&media!=null)media.offer(frame);
                // The remote audience still needs host frames even when the host cannot see the cabinet.
                if(audio!=null)audio.offer(frame.pcm48k());
                if(visible){
                if(texture==null||texture.getPixels()==null||texture.getPixels().getWidth()!=frame.width()||texture.getPixels().getHeight()!=frame.height()){
                    dropTexture();texture=new DynamicTexture(frame.width(),frame.height(),false);texture.setFilter(false,false);
                    textureId=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","external_cabinet_screen");
                    Minecraft.getInstance().getTextureManager().register(textureId,texture);
                }
                var pixels=texture.getPixels();for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++)pixels.setPixelRGBA(x,y,frame.abgr()[y*frame.width()+x]|0xff000000);
                texture.upload();aspect=frame.displayAspect();rotation=frame.rotation();
                }
            }
            drainMedia();
            if(visible&&textureId!=null){
                CabinetVideoDisplay.render(event,room==null?launch.target():room.primary(),textureId,aspect,rotation);
                if(room!=null&&room.secondary()!=null)CabinetVideoDisplay.render(event,room.secondary(),textureId,aspect,rotation);
            }
        }catch(RuntimeException|LinkageError failure){stop("模拟器画面异常："+failure.getMessage(),true,failure);}
    }
    private static void drainMedia(){
        if(media==null||room==null||!current())return;
        updateMediaDemand();
        for(int i=0;i<8;i++){
            var batch=media.pollOutbound();if(batch==null)break;
            if(receiver()||!needsMedia())continue; // A worker may finish an old batch after demand was cleared.
            var connection=Minecraft.getInstance().getConnection();
            if(connection!=null){boolean accepted=CabinetMediaSender.serverbound(connection.getConnection(),batch);media.transportResult(batch,accepted);}
        }
        if(audio!=null)for(int i=0;i<6;i++){short[] samples=media.pollAudio();if(samples==null)break;audio.offer(samples);}
    }
    private static void startNetplay(Path chosen,boolean remember){
        var mc=Minecraft.getInstance();var request=launch;var assignment=room;var grant=netplayGrant;var provider=backend;var key=selectionKey;
        var connection=mc.getConnection().getConnection();int token=++generation;
        try{
            String problem=provider.netplayUnavailableReason();if(problem!=null)throw new IllegalStateException(problem);
            var factory=Objects.requireNonNull(provider.prepareNetplayFactory());
            if(!netplay()||grant!=netplayGrant||!current())throw new IllegalStateException("Netplay launch cancelled");
            configureSelection=false;playing=true;announced=false;INPUT.reset();mc.setScreen(null);
            STARTER.execute(()->{
                try{
                    if(shuttingDown||generation!=token)return;
                    Path path=CabinetSharedGames.resolve(request,chosen,remember,provider,key,connection);
                    if(shuttingDown||generation!=token)return;
                    CabinetGameSelection.validate(path,provider.romExtensions(),provider.romExcludedNames());
                    var content=factory.open(path);
                    if(shuttingDown||generation!=token)return;
                    mc.execute(()->{
                        if(generation!=token||launch!=request||room!=assignment||netplayGrant!=grant||!current()||mc.getConnection().getConnection()!=connection)return;
                        try{emulator=new CabinetNetplayEmulator(grant,connection,content);audio=new CabinetAudio();
                            if(!guest()){media=new CabinetMediaStream(assignment.room(),assignment.hostMember(),true);updateMediaDemand();}}
                        catch(RuntimeException|LinkageError failure){stop("Netplay 启动失败："+failure.getMessage(),true,failure);}
                    });
                }catch(Exception|LinkageError failure){if(!shuttingDown)mc.execute(()->{if(generation==token)stop("Netplay 准备失败："+failure.getMessage(),true,failure);});}
                finally{OPENING.set(false);}
            });
        }catch(RuntimeException|LinkageError failure){OPENING.set(false);stop("Netplay 准备失败："+failure.getMessage(),true,failure);}
    }
    private static void startSynchronous(Path chosen,boolean remember){
        var mc=Minecraft.getInstance();var request=launch;var provider=backend;var key=selectionKey;var assignment=room;
        var preparedConnection=sessionConnection;var connection=mc.getConnection().getConnection();int preparedGeneration=generation;
        final CabinetBackend.SyncFactory factory;
        try{
            if(provider==null||!CabinetBackends.supportsSync(request.backend())||assignment==null
                    ||CabinetBackends.syncMaxPlayers(request.backend())<assignment.capacity())throw new IllegalStateException("该核心未通过当前席位数量的本地同步验证");
            String unavailable=provider.syncUnavailableReason();if(unavailable!=null)throw new IllegalStateException(unavailable);
            factory=Objects.requireNonNull(provider.prepareSyncFactory(key,mc.player.getUUID(),connection),"Missing sync factory");
        }catch(Exception|LinkageError failure){
            OPENING.set(false);
            if(launch==request&&room==assignment&&sessionConnection==preparedConnection&&generation==preparedGeneration)
                stop("本地同步准备失败："+failure.getMessage(),true,failure);
            return;
        }
        // An addon preparation callback must not revive a cancelled/replaced room or connection.
        if(launch!=request||room!=assignment||sessionConnection!=preparedConnection||generation!=preparedGeneration
                ||backend!=provider||selectionKey!=key||mc.getConnection()==null||mc.getConnection().getConnection()!=connection||!current()){
            try{factory.requestClose();}catch(RuntimeException|LinkageError ignored){}
            OPENING.set(false);
            if(launch==request&&room==assignment&&sessionConnection==preparedConnection&&generation==preparedGeneration)
                stop("本地同步上下文已失效",true);
            return;
        }
        int token=++generation;String expectedCompatibility=CabinetBackends.expectedSyncCompatibility(request.backend());
        try{
            configureSelection=false;playing=true;announced=false;INPUT.reset();mc.setScreen(null);
            if(launch!=request||room!=assignment||generation!=token||mc.getConnection()==null||mc.getConnection().getConnection()!=connection||!current())throw new IllegalStateException("Sync launch context changed");
            var worker=new CabinetSyncWorker(new CabinetSyncWorker.Factory(){
                @Override public CabinetSyncWorker.Opened open()throws Exception{
                Path path=CabinetSharedGames.resolve(request,chosen,remember,provider,key,connection);
                if(shuttingDown||token!=generation)throw new IllegalStateException("Sync launch cancelled");
                CabinetGameSelection.validate(path,provider.romExtensions(),provider.romExcludedNames());
                String hash=CabinetSharedGames.resolvedHash(request.lease());
                if(!CabinetSyncState.validHash(hash))throw new IllegalStateException("Shared ROM grant expired");
                var core=Objects.requireNonNull(factory.open(path),"Missing synchronized core");
                try{
                    if(shuttingDown||token!=generation||!connection.isConnected())throw new IllegalStateException("Sync launch cancelled after core initialization");
                    if(core.maxPlayers()<assignment.capacity())throw new IllegalStateException("Core player capacity mismatch");
                    if(expectedCompatibility!=null&&!expectedCompatibility.equals(core.compatibilityId()))throw new IllegalStateException("本地同步核心与服务器固定配置不一致");
                    return new CabinetSyncWorker.Opened(core,hash);
                }
                catch(Exception|LinkageError failure){try{core.close();}catch(RuntimeException|LinkageError ignored){}throw failure;}
                }
                @Override public void requestClose(){factory.requestClose();}
            },assignment.port()==0);
            emulator=worker;sync=new CabinetSyncClient(assignment,connection,worker);audio=new CabinetAudio();
            if(assignment.port()==0){media=new CabinetMediaStream(assignment.room(),assignment.hostMember(),true);updateMediaDemand();}
            notice("正在取得共享游戏并准备本地同步；追赶完成前不会授予输入");
        }catch(RuntimeException|LinkageError failure){try{factory.requestClose();}catch(RuntimeException|LinkageError ignored){}stop("本地同步启动失败："+failure.getMessage(),true,failure);}
        finally{OPENING.set(false);}
    }
    /** Shared-game authorization remains unchanged; no client native factory is opened in this mode. */
    private static void startHosted(Path chosen,boolean remember){
        var mc=Minecraft.getInstance();var request=launch;var assignment=room;var provider=backend;var key=selectionKey;
        var connection=mc.getConnection().getConnection();int token=++generation;
        configureSelection=false;playing=true;announced=false;INPUT.reset();mc.setScreen(null);
        try{STARTER.execute(()->{
            try{
                if(token!=generation||shuttingDown)return;
                if(remember)CabinetSharedGames.resolve(request,chosen,true,provider,key,connection);
                else CabinetSharedGames.authorizeHosted(request,connection);
                mc.execute(()->{
                    if(token!=generation||room!=assignment||launch!=request||!current()||mc.getConnection().getConnection()!=connection)return;
                    try{audio=new CabinetAudio();media=new CabinetMediaStream(assignment.room(),assignment.hostMember(),false,true);announced=true;
                        CabinetRoomNetwork.send(new CabinetRoomNetwork.Ready(assignment.room(),assignment.member()));}
                    catch(RuntimeException|LinkageError failure){stop("服务端音画接收器启动失败",true,failure);}
                });
            }catch(Exception|LinkageError failure){if(!shuttingDown)mc.execute(()->{if(token==generation)stop("服务端托管准备失败："+failure.getMessage(),true,failure);});}
            finally{OPENING.set(false);}
        });}catch(RejectedExecutionException failure){OPENING.set(false);stop("启动线程不可用，请重启客户端",true,failure);}
    }
}
