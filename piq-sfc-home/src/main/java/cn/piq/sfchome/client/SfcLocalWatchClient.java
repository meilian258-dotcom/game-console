package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.WatchAnchor;
import cn.piq.fcarcade.cabinet.WatchDescriptor;
import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.sfcarcade.audio.SfcAudioPlayer;
import cn.piq.sfcarcade.core.SfcCore;
import cn.piq.sfchome.SfcHomeMod;
import cn.piq.sfchome.net.*;
import cn.piq.sfchome.server.SfcLocalWatchTransfer;
import cn.piq.sfchome.server.SfcRepairLedger;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Receive-only local emulator, independent from the controlled HomeClient singleton. */
@EventBusSubscriber(modid="piq_sfc_home",value=Dist.CLIENT)
public final class SfcLocalWatchClient implements SfcLocalWatchNetwork.Client {
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"SFC-Local-Watch-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UUID,Upload> UPLOADS=new LinkedHashMap<>();
    private static Object connection;
    private static long revision=1,startedAt,lastReceived;
    private static int mode=SfcLocalWatchNetwork.LOCAL,ticks,quietUntil;
    private static Boolean available;
    private static boolean failed;
    private static volatile SfcLocalWatchNetwork.Start assignment;
    private static SfcPlayback playback;
    private static SfcLocalWatchTransfer transfer;
    private static int nextFrame;
    private static boolean restored,resumed;
    private static final class Upload {
        final SfcPlayback owner;final SfcRepairNetwork.Key key;final byte[] bytes;final String sha;final long began=System.nanoTime();int at;
        Upload(SfcPlayback owner,SfcRepairNetwork.Key key,SfcCheckpoints.Entry checkpoint){this.owner=owner;this.key=key;bytes=checkpoint.bytes();sha=checkpoint.sha();}
    }
    private SfcLocalWatchClient() {}
    public static void setup(){
        SfcLocalWatchNetwork.client(new SfcLocalWatchClient());
        cn.piq.fcarcade.client.PrivateHomeClient.registerBeforeStart(SfcLocalWatchClient::controlStarting);
        cn.piq.fcarcade.client.NetworkDiagnosticsClient.registerDevices("sfc-local-watch",()->{
            if(assignment==null||playback==null||!resumed||!hardwareCurrent())return List.of();var p=Minecraft.getInstance().player;
            return List.of(new cn.piq.fcarcade.client.NetworkDiagnosticsView.Device("SFC 本地旁观 @ "+assignment.session().consolePos().toShortString(),
                    cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.LOCAL_INPUT,cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.WATCHING,p.distanceToSqr(assignment.session().tvPos().getCenter())));
        });
    }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent e){
        e.getDispatcher().register(Commands.literal("sfc-watch")
                .executes(c->{notice("旁观模式："+(mode==1?"本地省流（Netplay 自动缓存当前游戏；旧输入同步需本机 ROM）":mode==0?"音画旁观":"关闭"));return 1;})
                .then(Commands.literal("local").executes(c->{select(SfcLocalWatchNetwork.LOCAL);return 1;}))
                .then(Commands.literal("media").executes(c->{select(SfcLocalWatchNetwork.MEDIA);return 1;}))
                .then(Commands.literal("off").executes(c->{select(SfcLocalWatchNetwork.OFF);return 1;}))
                .then(Commands.literal("rom").then(Commands.argument("path",StringArgumentType.greedyString()).executes(c->{importRom(StringArgumentType.getString(c,"path"));return 1;}))));
    }
    private static void select(int selected){stop(true);mode=selected;failed=false;revision++;available=null;
        notice(selected==1?"已选本地省流旁观：Netplay 自动缓存当前公开游戏；旧输入同步缺 ROM 时，用 /sfc-watch rom 路径 导入。":selected==0?"已选 SFC 音画旁观，网络流量较高。":"已关闭 SFC 旁观。");}
    public static int preferenceMode(){return mode;}
    public static void selectMode(int selected){if(selected<0||selected>2)throw new IllegalArgumentException("Observer mode");select(selected);}
    private static void importRom(String name){
        String path=name.strip();if(path.length()>1&&path.startsWith("\"")&&path.endsWith("\""))path=path.substring(1,path.length()-1);
        final Path selected;
        try{selected=Path.of(path);if(!selected.isAbsolute())throw new IllegalArgumentException();}catch(RuntimeException bad){notice("请填写本机 .sfc／.smc 的完整绝对路径。");return;}
        var mc=Minecraft.getInstance();var game=mc.gameDirectory.toPath();
        try{IO.execute(()->{try{byte[] bytes=SfcClientFiles.importRom(selected);String sha=SfcClientFiles.hash(bytes);SfcClientFiles.cacheRom(game,sha,bytes);
            mc.execute(()->{notice("SFC 本地 ROM 已校验导入（不上传）："+sha.substring(0,12)+"；用 /sfc-watch local 重试旁观。");});
        }catch(Exception bad){mc.execute(()->notice("本地导入失败："+Objects.requireNonNullElse(bad.getMessage(),bad.getClass().getSimpleName())));}});}catch(RejectedExecutionException busy){notice("SFC 文件任务繁忙，请稍后导入。");}
    }
    @Override public boolean acceptsConnection(Object source){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source;}
    static boolean busy(){return assignment!=null;}
    static boolean busy(WatchDescriptor source){return current(assignment)&&SfcWatchClient.matches(source,assignment.session());}
    static boolean suppressMedia(){return mode!=SfcLocalWatchNetwork.MEDIA;}
    /** Private startup has no public source identity and deliberately retains conservative cleanup. */
    static void controlStarting(){yieldLocalForControl();cn.piq.fcarcade.client.watch.WatchClient.controlStarting();}
    static void yieldLocalForControl(){stop(true);available=null;quietUntil=ticks+100;}
    /** Active cabinet startup runs off-thread; retire observation on the client thread, never steal its core. */
    public static void yieldForControl()throws Exception{
        if(!SfcCoreLease.observing()&&!busy())return;
        var mc=Minecraft.getInstance();
        if(mc.isSameThread()){yieldLocalForControl();return;}
        var yielded=new CompletableFuture<Void>();
        mc.execute(()->{try{yieldLocalForControl();yielded.complete(null);}catch(Throwable bad){yielded.completeExceptionally(bad);}});
        yielded.get(5,TimeUnit.SECONDS);
    }
    private static boolean availableNow(){
        var mc=Minecraft.getInstance();return ticks>=quietUntil&&mc.level!=null&&mc.player!=null&&mc.player.isAlive()&&mc.getConnection()!=null
                &&!cn.piq.retro.input.InputOwnership.occupied()&&!cn.piq.fcarcade.client.ClientArcadeEvents.isControlling()
                &&!cn.piq.fcarcade.client.PrivateHomeClient.isActiveOrClosing()
                &&SfcHomeClient.currentSession()==null&&(playback!=null||!SfcCoreLease.occupied());
    }
    private static boolean current(SfcLocalWatchNetwork.Start expected){return expected!=null&&assignment==expected&&connection!=null&&connection==Minecraft.getInstance().getConnection()&&mode==1&&!failed;}
    private static boolean owner(SfcPlayback p){return p!=null&&p==playback&&current(assignment);}
    static WatchDescriptor descriptor(SfcHomeNetwork.Session s){return new WatchDescriptor(SfcHomeMod.CABINET_BACKEND,s.mediaSource(),s.mediaStream(),s.dimension(),new WatchAnchor(s.consolePos(),s.consoleId()),s.linkId(),List.of(new WatchAnchor(s.tvPos(),s.tvId())));}
    private static boolean hardwareCurrent(){var mc=Minecraft.getInstance();return assignment!=null&&mc.player!=null&&SfcWatchClient.hardwareCurrent(descriptor(assignment.session()))&&mc.player.distanceToSqr(assignment.session().tvPos().getCenter())<=(double)assignment.exitRange()*assignment.exitRange();}
    private static SfcRepairNetwork.Key key(int frame){var s=assignment.session();return new SfcRepairNetwork.Key(s.sessionId(),s.epoch(),assignment.lease(),assignment.lease(),frame);}
    private static boolean keyCurrent(SfcRepairNetwork.Key key){return current(assignment)&&key.session()==assignment.session().sessionId()&&key.epoch()==assignment.session().epoch()&&key.lease().equals(assignment.lease())&&key.token().equals(assignment.lease());}
    @Override public void start(SfcLocalWatchNetwork.Start p){
        if(cn.piq.fcarcade.client.watch.WatchClient.hasNetplayWatch(descriptor(p.session()))){send(new SfcLocalWatchNetwork.Ack(p.lease(),2,true));return;}
        if(p.revision()!=revision||mode!=1||failed||!availableNow()||assignment!=null)return;
        connection=Minecraft.getInstance().getConnection();assignment=p;startedAt=lastReceived=System.nanoTime();nextFrame=p.frame();restored=resumed=false;transfer=null;
        if(!SfcHomeNetwork.CORE_BUILD.equals(p.session().coreBuild())||!hardwareCurrent()){fail("旁观设备或核心版本不匹配");return;}
        var mc=Minecraft.getInstance();var game=mc.gameDirectory.toPath();
        try{IO.execute(()->{try{byte[] rom=SfcClientFiles.cachedRom(game,p.session().romSha());mc.execute(()->{
            if(!current(p))return;if(rom==null){fail("本机没有此游戏 ROM：用 /sfc-watch rom <本机完整路径> 导入，再选 local；或选 media 音画旁观");return;}
            if(!availableNow()||!hardwareCurrent()){stop(true);return;}
            try{playback=new SfcPlayback(p.session(),rom,new SfcStartupProgress(),new ObserverHost());playback.beginRepair(key(p.frame()));}
            catch(RuntimeException|LinkageError bad){fail("无法启动本地旁观核心："+Objects.requireNonNullElse(bad.getMessage(),bad.getClass().getSimpleName()));}
        });}catch(Exception bad){mc.execute(()->{if(current(p))fail("本机 ROM 缓存读取或校验失败");});}});}catch(RejectedExecutionException busy){fail("本机文件任务繁忙");}
    }
    @Override public void stopped(SfcLocalWatchNetwork.Stopped p){if(p.revision()!=revision||assignment==null||!p.lease().equals(assignment.lease()))return;
        stop(false);if(!p.reason().isEmpty()){failed=true;notice(p.reason()+"；可 /sfc-watch local 手动重试，或 /sfc-watch media 选择音画旁观。");}}
    @Override public void capture(SfcLocalWatchNetwork.Capture p){
        var owner=SfcHomeClient.currentPlayback();var k=p.key();
        if(owner==null||!SfcHomeClient.isCurrent(owner)||!owner.session.executionHost()||!owner.session.checksState()
                ||!owner.matches(k.session(),k.epoch())||!owner.session.controllerLease().equals(k.lease())){send(new SfcLocalWatchNetwork.CaptureFailed(k));return;}
        if(UPLOADS.containsKey(k.token()))return;
        var checkpoint=owner.checkpoint(k.frame());
        if(checkpoint==null||UPLOADS.size()>=4){send(new SfcLocalWatchNetwork.CaptureFailed(k));return;}
        UPLOADS.put(k.token(),new Upload(owner,k,checkpoint));
    }
    @Override public void cancel(SfcLocalWatchNetwork.Cancel p){UPLOADS.remove(p.lease());}
    @Override public void state(SfcLocalWatchNetwork.State packet){
        var p=packet.value();if(!keyCurrent(p.key())||playback==null||restored||p.key().frame()!=assignment.frame())return;
        lastReceived=System.nanoTime();
        try{
            if(!assignment.sha().equals(p.sha()))throw new IllegalArgumentException();
            if(transfer==null){if(p.offset()!=0)throw new IllegalArgumentException();transfer=new SfcLocalWatchTransfer(p.total(),p.sha());}
            if(!transfer.append(p.total(),p.offset(),p.sha(),p.data()))throw new IllegalArgumentException();
            if(transfer.complete()){byte[] bytes=transfer.take();transfer=null;restored=true;playback.restoreRepair(key(assignment.frame()),bytes,assignment.sha());}
        }catch(RuntimeException invalid){fail("旁观检查点校验失败");}
    }
    @Override public void frames(SfcLocalWatchNetwork.Frames packet){
        var p=packet.value();if(!keyCurrent(p.key())||playback==null||!restored)return;
        lastReceived=System.nanoTime();
        if(p.key().frame()!=nextFrame||!playback.offerObserved(p)){fail("本地旁观追帧队列中断或积压");return;}nextFrame+=p.p1().length;
    }
    @Override public void resume(SfcLocalWatchNetwork.Resume p){if(!keyCurrent(p.key())||playback==null||!restored)return;
        lastReceived=System.nanoTime();if(resumed)return;
        if(p.key().frame()!=nextFrame){fail("本地旁观恢复边界不一致");return;}resumed=true;playback.resumeRepair(p.key());}
    private static final class ObserverHost implements SfcPlayback.Host {
        public boolean readOnlyObserver(){return true;}
        public void execute(Runnable action){Minecraft.getInstance().execute(action);}
        public boolean isCurrent(SfcPlayback p){return owner(p);}
        public void ready(SfcPlayback p,SfcHomeNetwork.Ready ready){if(owner(p))send(new SfcLocalWatchNetwork.Ready(assignment.lease(),ready.initialStateHash(),ready.targetFps()));}
        public void captured(SfcPlayback p,SfcJoinNetwork.Capture request,byte[] bytes,String sha){} // Never a host.
        public void applied(SfcPlayback p,SfcJoinNetwork.Capture request,String sha,boolean success){} // No controller join.
        public void backup(SfcPlayback p,SfcCore core,int frame){throw new IllegalStateException("Observers must not save");}
        public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){
            private SfcAudioPlayer audio=new SfcAudioPlayer();
            public void submit(short[] pcm,int frames,float gain){if(audio!=null)audio.submit(pcm,frames,gain);}
            public String failureMessage(){return audio==null?null:audio.failureMessage();}
            public void discardQueued(){close();audio=new SfcAudioPlayer();}
            public void close(){var old=audio;audio=null;if(old!=null)old.close();}
        };}
        public boolean consistencyChecks(){return true;}
        public void digest(SfcPlayback p,int frame,String sha){if(owner(p)&&resumed)send(new SfcLocalWatchNetwork.Digest(assignment.lease(),frame,sha));}
        public void repairedState(SfcPlayback p,SfcRepairNetwork.Key key,String sha,boolean success){if(!owner(p)||!keyCurrent(key))return;
            if(!success||!assignment.sha().equals(sha)){fail("旁观状态恢复后校验失败");return;}send(new SfcLocalWatchNetwork.Ack(assignment.lease(),0,true));}
        public void repairedFrames(SfcPlayback p,SfcRepairNetwork.Key key,boolean success){if(owner(p)){if(success)send(new SfcLocalWatchNetwork.Ack(assignment.lease(),1,true));else fail("旁观追帧失败");}}
        public void synchronizationFault(SfcPlayback p,int frame){if(owner(p))fail("本地旁观状态中断");}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();Object next=mc.getConnection();if(next!=connection){stop(false);UPLOADS.clear();connection=next;revision=1;available=null;failed=false;ticks=0;quietUntil=0;}
        if(next==null||mc.level==null||mc.player==null)return;
        ticks++;boolean can=availableNow()&&!failed;
        if(assignment!=null&&(!can||!hardwareCurrent())){stop(true);can=availableNow()&&!failed;}
        if(available==null||available!=can||ticks%40==0){available=can;send(new SfcLocalWatchNetwork.Preference(revision,mode,can));}
        if(assignment!=null){
            if(!resumed&&System.nanoTime()-startedAt>60_000_000_000L){fail("本地旁观准备超时");return;}
            if(resumed&&System.nanoTime()-lastReceived>10_000_000_000L){fail("本地旁观连接心跳超时");return;}
            if(playback!=null){if(playback.error()!=null){fail(playback.error());return;}
                float distance=(float)Math.max(0,1-Math.sqrt(mc.player.distanceToSqr(assignment.session().tvPos().getCenter()))/16);
                float gain=cn.piq.fcarcade.home.HomeApplianceService.audioGain(mc.level,assignment.session().tvPos())*distance
                        *mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.RECORDS);
                playback.gain(mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()?gain:0);
            }
        }
        // One shared two-part budget per host tick, not two per recipient.
        int budget=2;
        for(var entry:List.copyOf(UPLOADS.entrySet())){
            var u=entry.getValue();if(!SfcHomeClient.isCurrent(u.owner)||System.nanoTime()-u.began>60_000_000_000L){UPLOADS.remove(entry.getKey());continue;}
            while(budget>0&&u.at<u.bytes.length){int end=Math.min(u.bytes.length,u.at+SfcRepairLedger.CHUNK);
                var part=new SfcRepairNetwork.State(u.key,u.bytes.length,u.at,u.sha,Arrays.copyOfRange(u.bytes,u.at,end));
                if(!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(mc.getConnection().getConnection(),new SfcLocalWatchNetwork.Upload(part),end-u.at+2048,true)){budget=0;break;}
                u.at=end;budget--;
            }
            if(u.at==u.bytes.length)UPLOADS.remove(entry.getKey());if(budget==0)break;
        }
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent e){if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||playback==null||!current(assignment)||!hardwareCurrent())return;
        playback.upload();if(playback.textureId()==null||!resumed)return;var s=assignment.session();
        HomeVideoDisplay.render(e,SfcHomeMod.CABINET_BACKEND,s.consolePos(),s.consoleId(),s.tvPos(),s.tvId(),s.linkId(),playback.textureId(),(float)playback.aspect());}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){stop(false);UPLOADS.clear();connection=null;available=null;}
    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload p){var c=Minecraft.getInstance().getConnection();if(c!=null&&c==connection&&c.getConnection().isConnected())PacketDistributor.sendToServer(p);}
    private static void fail(String message){if(assignment!=null)send(new SfcLocalWatchNetwork.Ack(assignment.lease(),2,false));stop(false);failed=true;available=null;notice(message+"；不会自动切换高流量音画。");}
    private static void stop(boolean notify){var old=assignment;assignment=null;transfer=null;restored=resumed=false;if(notify&&old!=null)send(new SfcLocalWatchNetwork.Ack(old.lease(),2,true));
        var previous=playback;playback=null;if(previous!=null)previous.close();}
    private static void notice(String text){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal("[SFC旁观] "+text),false);}
}
