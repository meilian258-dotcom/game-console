// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.data.SfcControllerData;
import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.fcarcade.client.ClientArcadeEvents;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.retro.client.GamepadInput;
import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardInput;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.concurrent.*;
import java.util.Arrays;
import java.util.UUID;

@EventBusSubscriber(modid="piq_sfc_home",value=Dist.CLIENT)
public final class SfcHomeClient implements SfcHomeNetwork.ClientHandler {
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"SFC-Home-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ResourceLocation SYSTEM=ResourceLocation.fromNamespaceAndPath("piq_sfc_home","sfc");
    private static final Object INPUT_OWNER=new Object();
    private static UUID controlLease;
    private static int controlPort=-1;
    private static cn.piq.fcarcade.client.ControllerCapturePolicy.Held keyboardIdentity;
    private static final SfcControlGrantGate CONTROL_GATE=new SfcControlGrantGate();
    private static final SessionOrder SESSION_ORDER=new SessionOrder();
    private static SfcPlayback playback;
    private static SfcHomeNetwork.Session waiting;
    private static SfcHomeNetwork.NetplayStart waitingNetplay;
    @Override public void netplay(SfcHomeNetwork.NetplayStart value){session(value.session());if(waiting==value.session())waitingNetplay=value;}
    private static long waitingAt;
    private static Object sessionConnection;
    private static SfcStartupProgress startup;
    private static SfcStartupProgress.Stage shownStage;
    private static long startupToastAt;
    private static byte[] download;
    private static byte[] waitingRom;
    private static int downloadAt, sequence,lastMask=-1, keepalive;
    private static long lastInputSample;
    private static long lastMediaInterruption;
    private static boolean inactiveReleased;
    private static final SfcInputFocus INPUT_FOCUS=new SfcInputFocus();
    private SfcHomeClient() {}
    @EventBusSubscriber(modid="piq_sfc_home",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(()->{
            SfcHomeNetwork.setClientHandler(new SfcHomeClient());cn.piq.sfchome.net.SfcJoinNetwork.client(new SfcJoinClient());
            SfcLocalWatchClient.setup();
            cn.piq.fcarcade.client.NetworkDiagnosticsClient.registerDevices("sfc-home",SfcHomeClient::diagnosticDevices);
            cn.piq.sfchome.net.SfcRepairNetwork.client(new SfcRepairClient());
            cn.piq.fcarcade.client.ControllerCapture.register(SYSTEM,new cn.piq.fcarcade.client.ControllerCapture.Provider(){
                public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.SFC;}
                public int[][] keys(){return KeyboardInput.keys(SfcHomeKeys.KEYS);}
                public UUID lease(net.minecraft.world.item.ItemStack stack){return SfcControllerData.isController(stack)?SfcControllerData.leaseId(stack):null;}
                public boolean matches(net.minecraft.world.entity.player.Player player,net.minecraft.world.item.ItemStack stack,net.minecraft.world.level.block.entity.BlockEntity endpoint){
                    int port=SfcControllerData.port(stack);return endpoint instanceof cn.piq.sfchome.world.SfcHomeConsoleBlockEntity console
                            &&cn.piq.fcarcade.client.ControllerCapturePolicy.receipt(player.getUUID(),lease(stack),port,console.controllerVisualPlayer(port),console.controllerVisualLease(port),console.controllerDocked(port),player.distanceToSqr(console.getBlockPos().getCenter()));
                }
            });
            cn.piq.fcarcade.client.ControllerCapture.registerRuntime(SfcHomeClient::refreshKeyboardCapture);
            cn.piq.fcarcade.client.PrivateHomeClient.register(SYSTEM,new SfcPrivateProvider());
        }); }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { SfcHomeKeys.register(event);KeyboardInput.registerLegacy(KeyboardConfig.Profile.SFC,()->KeyboardInput.keys(SfcHomeKeys.KEYS)); }
    }
    @Override public boolean acceptsConnection(Object source){var c=Minecraft.getInstance().getConnection();return SessionOrder.sameConnection(source,c==null?null:c.getConnection());}
    static boolean isCurrent(SfcPlayback p){return p!=null&&playback==p&&SessionOrder.sameConnection(sessionConnection,Minecraft.getInstance().getConnection());}
    static SfcPlayback currentPlayback(){return playback;}
    /** Visual-only snapshot; never polls keys, acquires ownership or sends a packet. */
    static int visualInputMask(){return acceptsInput()&&lastMask>=0?lastMask&0xfff:0;}
    static SfcHomeNetwork.Session currentSession(){return playback==null?waiting:playback.session;}
    /** Read-only report of the admitted session, not the console's editable next-session preference. */
    private static java.util.List<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device> diagnosticDevices(){
        var mc=Minecraft.getInstance();var s=currentSession();
        if(s==null||!sessionCurrent(s,mc.getConnection())||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected()
                ||mc.level==null||mc.player==null||!s.dimension().equals(mc.level.dimension().location()))return java.util.List.of();
        var role=presentController()?cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.CONTROLLING
                :controlPort>=0?cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.SEATED:cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.COMPUTING;
        return java.util.List.of(new cn.piq.fcarcade.client.NetworkDiagnosticsView.Device("SFC @ "+s.consolePos().toShortString(),
                s.syncMode()==3?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.NETPLAY:s.serverHosted()?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.SERVER_MEDIA:s.playerHosted()?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.PLAYER_MEDIA:cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.LOCAL_INPUT,
                role,mc.player.distanceToSqr(s.consolePos().getCenter())));
    }
    static boolean sessionCurrent(SfcHomeNetwork.Session expected,Object connection){return expected!=null&&expected==currentSession()&&SessionOrder.sameConnection(connection,sessionConnection)&&SessionOrder.sameConnection(connection,Minecraft.getInstance().getConnection());}
    static void refreshInput(){lastMask=-1;inactiveReleased=false;}
    static void releaseRepairInput(){sendInput(true);}
    /** Session/held-controller authority, independent of GUI, focus and the local control mode. */
    private static boolean presentController(){var mc=Minecraft.getInstance();return controlPort>=0&&controlLease!=null&&isCurrent(playback)&&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected()&&!ClientArcadeEvents.isControlling()&&cn.piq.retro.input.InputOwnership.owns(INPUT_OWNER)&&mc.player!=null&&mc.player.isAlive()&&!mc.player.isSpectator()&&mc.level!=null
            &&mc.level.dimension().location().equals(playback.session.dimension())&&cn.piq.sfchome.server.SfcControllerAuthority.withinCableDistance(mc.player.distanceToSqr(playback.session.consolePos().getCenter()))&&localController(playback.session,true)!=null;}
    private static boolean ownsController(){return presentController()&&playback.started()&&!playback.mediaInputPaused()&&!SfcRepairClient.suspended(playback);}
    static boolean acceptsInput(){var mc=Minecraft.getInstance();return ownsController()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();}
    private static boolean attachControls(){
        if(!presentController())return false;
        var held=localController(playback.session,true);var p=Minecraft.getInstance().player;
        var next=new cn.piq.fcarcade.client.ControllerCapturePolicy.Held(controlLease,controlPort,p.getMainHandItem()==held?0:1,false);
        if(!next.equals(keyboardIdentity)){keyboardIdentity=next;KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);sendInput(true);}
        return KeyboardInput.attach(INPUT_OWNER,KeyboardConfig.Profile.SFC,()->KeyboardInput.keys(SfcHomeKeys.KEYS),SfcHomeClient::presentController,SfcHomeClient::ownsController,()->sendInput(true),()->sendInput(false));
    }
    private static void refreshKeyboardCapture(){if(presentController())attachControls();else {keyboardIdentity=null;KeyboardInput.release(INPUT_OWNER);if(playback!=null)GamepadInput.pause(playback);}}
    private static net.minecraft.world.item.ItemStack localController(SfcHomeNetwork.Session s,boolean heldOnly){
        var p=Minecraft.getInstance().player;if(p==null||s==null)return null;
        var items=new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        if(heldOnly){items.add(p.getMainHandItem());items.add(p.getOffhandItem());}
        else{for(int i=0;i<p.getInventory().getContainerSize();i++)items.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)items.add(slot.getItem());items.add(p.containerMenu.getCarried());}
        if(controlLease==null||controlPort<0)return null;
        var found=cn.piq.sfchome.server.SfcControllerInventory.unique(controlLease,items,item->SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null,net.minecraft.world.item.ItemStack::getCount);
        return found!=null&&SfcControllerData.port(found)==controlPort&&(!heldOnly||cn.piq.fcarcade.client.ControllerCapture.unique(p,found,controlLease,
                item->SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null))?found:null;
    }
    @Override public void session(SfcHomeNetwork.Session message) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.getConnection()==null||!mc.level.dimension().location().equals(message.dimension())||!SfcHomeNetwork.CORE_BUILD.equals(message.coreBuild())) {
            if(mc.getConnection()!=null)SfcHomeNetwork.leave(message.controllerLease(),new SfcHomeNetwork.Leave(message.sessionId(),message.epoch()));toast("SFC 核心或维度不匹配");return;
        }
        // Another host's still-running session may have a lower ID. Track exact
        // assignments, not globally monotone IDs, and never revive one already left.
        if(!SESSION_ORDER.accept(mc.getConnection(),message.sessionId(),message.epoch(),message.controllerLease())){
            if(SESSION_ORDER.limitReached()){SfcHomeNetwork.leave(message.controllerLease(),new SfcHomeNetwork.Leave(message.sessionId(),message.epoch()));toast("SFC 本次连接的会话记录已满，请重新连接服务器后再领取手柄");}
            return;
        }
        cn.piq.fcarcade.client.PrivateHomeClient.stop("收到公开 SFC 游戏会话");
        cn.piq.fcarcade.client.watch.WatchClient.controlStarting();
        SfcLocalWatchClient.controlStarting();
        closeLocal();
        waitingNetplay=null;
        if(!message.executionHost()&&(ClientArcadeEvents.isControlling()||!CabinetClientOwner.acquire(INPUT_OWNER))){SfcHomeNetwork.leave(message.controllerLease(),new SfcHomeNetwork.Leave(message.sessionId(),message.epoch()));toast("请先退出当前模拟器的控制，再领取 SFC 手柄");return;}
        if(!message.executionHost()){controlLease=message.controllerLease();controlPort=message.port();}
        waiting=message;waitingAt=System.nanoTime();sequence=0;lastMask=-1;sessionConnection=mc.getConnection();startup=new SfcStartupProgress();
        CONTROL_GATE.bind(sessionConnection,message.sessionId(),message.epoch());
        if(message.receivesMedia()){begin(message,new byte[0]);return;}
        Object connection=sessionConnection;var game=mc.gameDirectory.toPath();
        if(!submitIo(()-> {
            try { byte[] bytes=SfcClientFiles.cachedRom(game,message.romSha());
                mc.execute(()->{if(!waitingCurrent(message,connection))return;if(bytes==null){startup.enter(SfcStartupProgress.Stage.DOWNLOAD);PacketDistributor.sendToServer(new SfcHomeNetwork.RomRequest(message.romSha()));}else begin(message,bytes);});
            }catch(Exception error){mc.execute(()->{if(waitingCurrent(message,connection))fail("SFC 准备失败",error);});}
        }))leave("SFC 文件任务繁忙，请归还后重新领取");
    }
    private static boolean submitIo(Runnable task){try{IO.execute(task);return true;}catch(RejectedExecutionException busy){return false;}}
    private static boolean waitingCurrent(SfcHomeNetwork.Session message,Object connection){return waiting==message&&sessionConnection==connection&&connection!=null&&Minecraft.getInstance().getConnection()==connection;}
    private static void begin(SfcHomeNetwork.Session message,byte[] bytes){if(!waitingCurrent(message,sessionConnection))return;download=null;waitingRom=bytes;startup.enter(SfcStartupProgress.Stage.HARDWARE);maybeBegin();}
    private static boolean hardwareCurrent(SfcHomeNetwork.Session s){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||!mc.level.dimension().location().equals(s.dimension())
                ||(!s.executionHost()&&localController(s,false)==null)
                ||!mc.level.hasChunkAt(s.consolePos())||!mc.level.hasChunkAt(s.tvPos()))return false;
        return mc.level.getBlockEntity(s.consolePos()) instanceof cn.piq.sfchome.world.SfcHomeConsoleBlockEntity console
                &&mc.level.getBlockEntity(s.tvPos()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv
                &&s.consoleId().equals(console.hardwareId())&&s.tvId().equals(tv.hardwareId())
                &&s.linkId().equals(console.linkId())&&s.linkId().equals(tv.linkId())
                &&s.tvPos().equals(console.televisionPos())&&s.consolePos().equals(tv.consolePos())
                &&cn.piq.fcarcade.home.HomeTvStructure.complete(mc.level,s.tvPos());
    }
    private static void maybeBegin(){
        if(waiting==null||waitingRom==null||!hardwareCurrent(waiting))return;
        if(!waiting.receivesMedia()&&SfcCoreLease.occupied()){startup.enter(SfcStartupProgress.Stage.PREVIOUS_CORE);return;}
        try{playback=new SfcPlayback(waiting,waitingRom,startup,waitingNetplay);waiting=null;waitingRom=null;waitingNetplay=null;}
        catch(RuntimeException error){if(SfcCoreLease.occupied())startup.enter(SfcStartupProgress.Stage.PREVIOUS_CORE);else fail("SFC 准备失败",error);}
    }
    @Override public void romChunk(SfcHomeNetwork.RomChunk chunk) {
        SfcNetplayWatchContent.chunk(chunk);
        if(waiting==null||waiting.receivesMedia()||!waitingCurrent(waiting,sessionConnection)||!waiting.romSha().equals(chunk.romSha()))return;
        if(chunk.total()<32768||chunk.total()>SfcClientFiles.MAX_ROM||chunk.offset()<0||chunk.data().length>65536){leave("SFC ROM 分片无效");return;}
        if(download==null){if(chunk.offset()!=0){leave("SFC ROM 缺失首片");return;}download=new byte[chunk.total()];downloadAt=0;}
        if(chunk.total()!=download.length||chunk.offset()!=downloadAt||chunk.data().length>download.length-downloadAt){leave("SFC ROM 分片顺序错误");return;}
        System.arraycopy(chunk.data(),0,download,downloadAt,chunk.data().length);downloadAt+=chunk.data().length;
        startup.download(downloadAt,download.length);
        if(downloadAt!=download.length)return;
        var expected=waiting;byte[] complete=download;download=null;startup.enter(SfcStartupProgress.Stage.CACHE_WRITE);
        var mc=Minecraft.getInstance();Object connection=sessionConnection;var game=mc.gameDirectory.toPath();
        if(!submitIo(()->{try{SfcClientFiles.cacheRom(game,expected.romSha(),complete);
            mc.execute(()->{if(waitingCurrent(expected,connection))begin(expected,complete);});}
            catch(Exception error){mc.execute(()->{if(waitingCurrent(expected,connection))fail("SFC 准备失败",error);});}}))leave("SFC 缓存任务繁忙，请归还后重新领取");
    }
    @Override public void frames(SfcHomeNetwork.Frames message) {
        if(isCurrent(playback)&&!playback.session.receivesMedia()&&playback.matches(message.sessionId(),message.epoch())&&!playback.offer(message))leave("SFC 模拟跟不上输入，已安全停止");
    }
    @Override public void hosted(cn.piq.sfchome.net.SfcHostedNetwork.Stream message){
        if(isCurrent(playback)&&hardwareCurrent(playback.session))playback.offerMedia(message);
    }
    @Override public void hostedReset(cn.piq.sfchome.net.SfcHostedNetwork.Reset message){
        if(isCurrent(playback)&&playback.matches(message.session(),message.epoch())&&playback.session.controllerLease().equals(message.recipient())){playback.resetMedia();sendInput(true);refreshInput();}
    }
    @Override public void stopped(SfcHomeNetwork.Stopped message) {
        var s=currentSession();
        if(sessionCurrent(s,sessionConnection)&&s.sessionId()==message.sessionId()&&s.epoch()==message.epoch()){SfcJoinClient.stopped(message.sessionId(),message.epoch());closeLocal();toast(message.reason());}
    }
    @Override public void control(SfcHomeNetwork.Control message){
        var s=currentSession();if(!sessionCurrent(s,sessionConnection)||s.sessionId()!=message.sessionId()||s.epoch()!=message.epoch()||!s.executionHost())return;
        if(!message.active()){
            CONTROL_GATE.retire(sessionConnection,message.sessionId(),message.epoch(),message.lease());
            if(message.lease().equals(controlLease))releaseControl();
            return;
        }
        if(!CONTROL_GATE.mayGrant(sessionConnection,message.sessionId(),message.epoch(),message.lease())){SfcHomeNetwork.leave(message.lease(),new SfcHomeNetwork.Leave(s.sessionId(),s.epoch()));return;}
        if(message.lease().equals(controlLease)&&message.port()==controlPort)return;
        if(controlLease!=null||ClientArcadeEvents.isControlling()||!CabinetClientOwner.acquire(INPUT_OWNER)){
            SfcHomeNetwork.leave(message.lease(),new SfcHomeNetwork.Leave(s.sessionId(),s.epoch()));toast("当前已有其他模拟器控制，SFC 继续后台播放");return;
        }
        controlLease=message.lease();controlPort=message.port();sequence=0;refreshInput();INPUT_FOCUS.reset();
    }
    private static void releaseControl(){
        sendInput(true);controlLease=null;controlPort=-1;keyboardIdentity=null;KeyboardInput.release(INPUT_OWNER);CabinetClientOwner.release(INPUT_OWNER);
        if(playback!=null)GamepadInput.release(playback);INPUT_FOCUS.reset();lastMask=-1;inactiveReleased=false;keepalive=0;lastInputSample=0;lastMediaInterruption=0;
    }
    @Override public void editor(SfcHomeNetwork.Editor message) {
        var mc=Minecraft.getInstance();
        if(mc.screen instanceof SfcCardEditorScreen screen&&screen.token().equals(message.token()))screen.update(message);
        else if(message.open()&&mc.screen==null&&mc.player!=null&&mc.player.getMainHandItem().is(cn.piq.sfchome.registry.SfcHomeRegistries.CARTRIDGE.get()))mc.setScreen(new SfcCardEditorScreen(message));
        else if(message.open())PacketDistributor.sendToServer(new SfcHomeNetwork.EditorAction(message.token(),SfcHomeNetwork.CANCEL,"","",0,0,new byte[0]));
        else if(!message.message().isBlank())cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(message.message());
    }
    static void leave(String reason) {
        var s=playback==null?waiting:playback.session;
        if(s!=null&&sessionConnection!=null&&Minecraft.getInstance().getConnection()==sessionConnection)SfcHomeNetwork.leave(s.controllerLease(),new SfcHomeNetwork.Leave(s.sessionId(),s.epoch()));
        closeLocal();if(reason!=null&&!reason.isBlank())toast(reason);
    }
    private static void closeLocal(){SfcRepairClient.clear();SfcJoinClient.clear();releaseControl();CONTROL_GATE.clear();waiting=null;waitingRom=null;waitingNetplay=null;download=null;downloadAt=0;sessionConnection=null;startup=null;shownStage=null;startupToastAt=0;if(playback!=null){playback.close();playback=null;}SfcHomeKeys.restore();}
    private static void showStartup(){
        if(startup==null)return;var stage=startup.stage();long now=System.nanoTime();
        if(stage!=shownStage||(stage!=SfcStartupProgress.Stage.READY&&stage!=SfcStartupProgress.Stage.RUNNING&&now-startupToastAt>2_000_000_000L)){
            shownStage=stage;startupToastAt=now;
        }
    }
    private static void fail(String reason,Throwable failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("SFC",reason,failure);leave(null);}
    static void toast(String message){cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("SFC",message);}
    private static void sendInput(boolean requestedRelease){
        if(playback==null||controlLease==null||controlPort<0||sessionConnection==null||Minecraft.getInstance().getConnection()!=sessionConnection)return;
        // A decode worker may stall and recover between two input samples. Keep the
        // interruption latched until this control path actually sends a release.
        long mediaRevision=playback.mediaInterruption();
        if(mediaRevision!=lastMediaInterruption){lastMediaInterruption=mediaRevision;requestedRelease=true;}
        // Reloads can block all client ticks and leave the previous button held on the server.
        // The first live sample clears old edges and requires physical release before re-arming.
        long sampledAt=System.nanoTime();
        requestedRelease|=SfcInputSendPolicy.resumingAfterStall(lastInputSample,sampledAt);
        lastInputSample=sampledAt;
        boolean active=acceptsInput();
        int mask=0;
        if(!requestedRelease&&attachControls()){
            var sample=KeyboardInput.poll(INPUT_OWNER,active?SfcHomeKeys.poll():0,active);
            active=active&&sample.enabled()&&sample.armed();
            mask=sample.mask();
        }else active=false;
        // GUI/key callbacks may run before the next tick. Clear queued edges once
        // on every inactive transition, even if a prior ordinary release sent 0.
        boolean force=requestedRelease||(!active&&!inactiveReleased);
        if(active)inactiveReleased=false;
        else if(force)inactiveReleased=true;
        if(force||!active){mask=0;INPUT_FOCUS.suspend();KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);}
        else {mask=GamepadInput.mix(playback,GamepadInput.ProfileKind.SFC,mask,true);mask=INPUT_FOCUS.sample(mask);}
        if(SfcInputSendPolicy.shouldSend(force,mask,lastMask,keepalive)){
            playback.netplayInput(mask);
            var input=new SfcHomeNetwork.Input(playback.session.sessionId(),playback.session.epoch(),sequence++,mask,force);
            PacketDistributor.sendToServer(new cn.piq.sfchome.net.SfcJoinNetwork.ControllerInput(controlLease,input));
            lastMask=mask;keepalive=0;
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null){closeLocal();return;}
        if(sessionConnection!=null&&mc.getConnection()!=sessionConnection){closeLocal();return;}
        if(controlLease!=null&&ClientArcadeEvents.isControlling()){var s=currentSession();SfcHomeNetwork.leave(controlLease,new SfcHomeNetwork.Leave(s.sessionId(),s.epoch()));if(s.executionHost()){releaseControl();toast("已归还 SFC 控制，主机仍在后台运行");}else{leave("已切换到 FC 控制，SFC 手柄已归还");return;}}
        if(waiting!=null&&System.nanoTime()-waitingAt>90_000_000_000L){leave("SFC 准备超时："+(startup==null?"请重新领取手柄":startup.message()));return;}
        maybeBegin();
        SfcJoinClient.tick();
        SfcRepairClient.tick();
        showStartup();
        if(playback!=null) {
            if(!mc.level.dimension().location().equals(playback.session.dimension())){leave("");return;}
            if(playback.error()!=null){leave(playback.error());return;}
            String mediaNotice=playback.pollMediaNotice();if(mediaNotice!=null)cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(mediaNotice);
            String audioNotice=playback.pollAudioNotice();if(audioNotice!=null)cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(audioNotice);
            double distance=mc.player.distanceToSqr(playback.session.tvPos().getX()+.5,playback.session.tvPos().getY()+.5,playback.session.tvPos().getZ()+.5);
            playback.gain((float)(.65*cn.piq.fcarcade.home.HomeApplianceService.audioGain(mc.level,playback.session.tvPos())*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS)*Math.max(0,1-Math.sqrt(distance)/16)));
        }
        boolean active=acceptsInput();SfcHomeKeys.sync(active);
        if(playback!=null){keepalive++;sendInput(false);}
    }
    @SubscribeEvent public static void key(InputEvent.Key event){if(playback!=null)sendInput(false);}
    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event){if(event.getNewScreen()!=null){INPUT_FOCUS.suspend();sendInput(true);}}
    // Mouse input keeps vanilla look/use/attack, including right-click controller return.
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre event){if(playback!=null)sendInput(false);}
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(!isCurrent(playback)||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        // Capture physical pad edges between ticks through the existing lease-bound
        // input path. Only tick advances the keepalive, never render/key callbacks.
        sendInput(false);
        playback.upload();var s=playback.session;
        if(playback.textureId()!=null)HomeVideoDisplay.render(event,SYSTEM,s.consolePos(),s.consoleId(),s.tvPos(),s.tvId(),s.linkId(),playback.textureId(),playback.aspect());
    }
    /** Pure connection-identity and bounded assignment replay fence; survives local leave. */
    static final class SessionOrder {
        private Object connection;private boolean limitReached;
        private final java.util.Set<Assignment> seen=new java.util.HashSet<>();
        private record Assignment(long session,int epoch,java.util.UUID lease){}
        static boolean sameConnection(Object expected,Object actual){return expected!=null&&expected==actual;}
        boolean accept(Object source,long nextId,int nextEpoch,java.util.UUID lease){
            limitReached=false;
            if(source==null||nextId<=0||nextEpoch<=0||lease==null)return false;
            if(source!=connection){seen.clear();connection=source;}
            var key=new Assignment(nextId,nextEpoch,lease);
            if(seen.contains(key))return false;
            if(seen.size()>=256){limitReached=true;return false;}
            seen.add(key);return true;
        }
        boolean limitReached(){return limitReached;}
    }
}
