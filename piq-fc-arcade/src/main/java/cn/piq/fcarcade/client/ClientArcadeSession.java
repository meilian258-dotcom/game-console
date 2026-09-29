package cn.piq.fcarcade.client;

import cn.piq.fcarcade.*;
import cn.piq.fcarcade.audio.NesAudioPlayer;
import cn.piq.fcarcade.audio.SpatialAudio;
import cn.piq.fcarcade.config.ArcadeGlobalSettings;
import cn.piq.fcarcade.core.NesButton;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.session.ArcadeMode;
import cn.piq.fcarcade.session.ArcadeRole;
import cn.piq.fcarcade.session.LockstepState;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.lwjgl.system.MemoryUtil;
import java.util.ArrayList;

/** Main-thread facade: never constructs or accesses a core on this thread. */
final class ClientArcadeSession {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final byte[] rgba = new byte[NesCore.RGBA_BYTES];
    private ResourceKey<Level> dimension;
    private BlockPos arcadePos;
    private ClientNesWorker worker;
    private cn.piq.fcarcade.netplay.NetplayNetwork.State netplayState;
    private cn.piq.fcarcade.netplay.NetplayProcess netplay;
    private java.util.UUID usedNetplayTicket;
    void netplayAuthority(cn.piq.fcarcade.netplay.NetplayNetwork.State value){netplayState=value;}
    private NesAudioPlayer audioPlayer;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private int inputMask;
    private final ControllerInputCapture inputCapture = new ControllerInputCapture();
    private boolean resetWasDown, muteWasDown, audioMuted;
    private boolean simulationEnabled = true;
    private boolean workerReady, requestSyncOnReady, awaitingSnapshot;
    private boolean calibrationStarted, pendingSnapshotRequest;
    private long nextSyncRequestNanos;
    private ArcadeRole role = ArcadeRole.SPECTATOR;
    private ArcadeMode mode = ArcadeMode.STREAM;
    private long sessionId = -1;
    private int epoch, inputSequence;
    private long localFrame, presentationSequence, silentUntilFrame;
    private int viewDistance = ArcadeGlobalSettings.DEFAULT_VIEW_DISTANCE;
    private int audioDistance = ArcadeGlobalSettings.DEFAULT_AUDIO_DISTANCE;
    private int audioVolumePercent = ArcadeGlobalSettings.DEFAULT_AUDIO_VOLUME_PERCENT;
    private String playerNames = "";
    private String romSha256;
    private Object keyboardConnection;
    private cn.piq.fcarcade.session.NesCoreVariant variant=cn.piq.fcarcade.session.NesCoreVariant.LIBRETRO_V1;
    private boolean zapperControlsEnabled;
    private boolean homeRuntime,computeHost,playerMedia;
    private FcPlayerMediaPublisher mediaPublisher;
    private java.util.UUID controllerLease;
    private long controlRevision;
    private boolean homeReadySent;
    private java.util.UUID hostedSource,hostedToken;
    private boolean hostedOperator;
    private cn.piq.fcarcade.client.cabinet.WatchMediaStream hostedMedia;
    private cn.piq.fcarcade.client.cabinet.WatchAudio hostedAudio;
    private boolean serverHosted(){return hostedSource!=null&&hostedToken!=null;}
    void hostedAuthority(java.util.UUID source,java.util.UUID token,boolean operator){
        if(isActive()&&(!java.util.Objects.equals(hostedSource,source)||!java.util.Objects.equals(hostedToken,token)))closeResources();
        hostedSource=source;hostedToken=token;hostedOperator=operator;
    }
    void hostedMedia(cn.piq.fcarcade.server.FcHomeHostedNetwork.Stream stream){
        if(!serverHosted()||!connected()||minecraft.level==null||minecraft.player==null||dimension!=minecraft.level.dimension()||!stream.matches(sessionId,epoch,hostedSource,hostedToken,minecraft.player.getUUID())||hostedMedia==null)return;
        var part=stream.media();if(!hostedSource.equals(part.room())||!hostedToken.equals(part.hostMember()))return;
        hostedMedia.accept(new cn.piq.fcarcade.cabinet.CabinetMediaPacket(part.room(),part.hostMember(),part.sequence(),part.kind(),part.index(),part.count(),part.width(),part.height(),part.aspect(),part.rotation(),part.rawLength(),part.data()));
    }
    private void startHostedMedia(){
        if(hostedMedia!=null)hostedMedia.close();if(hostedAudio!=null)hostedAudio.close();
        hostedMedia=new cn.piq.fcarcade.client.cabinet.WatchMediaStream(hostedSource,hostedToken,false);
        hostedAudio=new cn.piq.fcarcade.client.cabinet.WatchAudio();workerReady=true;awaitingSnapshot=false;
    }
    private final HomeInputSequences homeInputSequences=new HomeInputSequences();
    private ControllerCapturePolicy.Held keyboardIdentity;
    void applianceAuthority(boolean home,boolean host,java.util.UUID lease,long revision){
        applianceAuthority(home,host,lease,revision,false);
    }
    void applianceAuthority(boolean home,boolean host,java.util.UUID lease,long revision,boolean media){
        if(home&&homeRuntime&&revision<controlRevision)return;
        if(!java.util.Objects.equals(controllerLease,lease)){releaseControls();inputSequence=homeInputSequences.switchLease(controllerLease,inputSequence,lease);}
        homeRuntime=home;computeHost=host;controllerLease=lease;controlRevision=revision;playerMedia=media;
    }
    boolean acceptsMediaDemand(cn.piq.fcarcade.cabinet.WatchDescriptor d){
        // A recovering core is temporarily not ready, but its media source/sequence is still current.
        return playerMedia&&homeRuntime&&computeHost&&worker!=null&&connected()
                &&d!=null&&FcHomeWatchDisplay.PROVIDER.equals(d.provider())&&d.source().equals(new java.util.UUID(sessionId,epoch))
                &&dimension!=null&&d.dimension().equals(dimension.location())&&d.link()!=null&&d.screens().size()==1
                &&d.screens().getFirst().pos().equals(arcadePos);
    }
    void mediaDemand(cn.piq.fcarcade.cabinet.WatchNetwork.HostDemand d){
        if(!acceptsMediaDemand(d.descriptor()))return;
        if(mediaPublisher==null){mediaPublisher=new FcPlayerMediaPublisher(this);worker.mediaTap(mediaPublisher);}
        mediaPublisher.demand(d);
    }
    boolean acceptsApplianceRevision(long revision){return !homeRuntime||revision>=controlRevision;}
    boolean isComputeHost(){return isActive()&&computeHost;}
    boolean isHomeOperator(){return isActive()&&(computeHost||serverHosted()&&hostedOperator);}
    private boolean snapshotOwner(){return !serverHosted()&&(homeRuntime?computeHost:role==ArcadeRole.PLAYER_ONE);}

    void join(BlockPos pos, ArcadeMode nextMode, ArcadeRole nextRole,
              long nextSessionId, int nextViewDistance, int nextAudioDistance,
              int nextAudioVolumePercent, int nextMemberCount, String nextPlayerNames,
              String expectedRomSha256, int nextEpoch, boolean reset) {
        join(pos,nextMode,nextRole,nextSessionId,nextViewDistance,nextAudioDistance,nextAudioVolumePercent,nextMemberCount,nextPlayerNames,
                expectedRomSha256,nextEpoch,reset,cn.piq.fcarcade.session.NesCoreVariant.LIBRETRO_V1);
    }
    void join(BlockPos pos, ArcadeMode nextMode, ArcadeRole nextRole,
              long nextSessionId, int nextViewDistance, int nextAudioDistance,
              int nextAudioVolumePercent, int nextMemberCount, String nextPlayerNames,
              String expectedRomSha256, int nextEpoch, boolean reset,cn.piq.fcarcade.session.NesCoreVariant nextVariant) {
        viewDistance = nextViewDistance;
        audioDistance = nextAudioDistance;
        audioVolumePercent = nextAudioVolumePercent;
        playerNames = nextPlayerNames;
        if (isActiveAt(pos) && sessionId == nextSessionId) {
            if(variant!=nextVariant){stop(Component.literal("本局核心类型发生变化，已停止以避免状态混用"));return;}
            setRole(nextRole);
            if(netplayState!=null){
                if(simulationEnabled&&(netplay==null||!netplay.grant().ticket().equals(netplayState.ticket())))startWorker(false);
                return;
            }
            if (nextMode == ArcadeMode.LOCKSTEP && (reset || epoch != nextEpoch)) restartLockstep(nextEpoch);
            return;
        }
        boolean keepHome=homeRuntime,keepHost=computeHost,keepMedia=playerMedia;var keepLease=controllerLease;long keepRevision=controlRevision;
        var keepSource=hostedSource;var keepToken=hostedToken;boolean keepOperator=hostedOperator;
        var keepNetplay=netplayState;
        closeResources();homeRuntime=keepHome;computeHost=keepHost;playerMedia=keepMedia;controllerLease=keepLease;controlRevision=keepRevision;hostedSource=keepSource;hostedToken=keepToken;hostedOperator=keepOperator;
        netplayState=keepNetplay;
        if (minecraft.level == null || minecraft.player == null) return;
        mode = nextMode;
        variant=nextVariant;
        role = nextRole;
        sessionId = nextSessionId;
        epoch = nextEpoch;
        dimension = minecraft.level.dimension();
        keyboardConnection = minecraft.getConnection();
        arcadePos = pos.immutable();
        romSha256 = expectedRomSha256;
        viewDistance = nextViewDistance;
        audioDistance = nextAudioDistance;
        audioVolumePercent = nextAudioVolumePercent;
        playerNames = nextPlayerNames;
        if (role.controllerIndex() >= 0||computeHost) simulationEnabled = true;
        try {
            texture = new DynamicTexture(NesCore.WIDTH, NesCore.HEIGHT, false);
            texture.setFilter(false, false);
            textureId = minecraft.getTextureManager().register("piq_fc_arcade_block_screen", texture);
            silentUntilFrame = 3;
            uploadSyncFrame();
            if(serverHosted())startHostedMedia();else if (simulationEnabled) startWorker(!computeHost&&role == ArcadeRole.SPECTATOR);
        } catch (Throwable error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 启动方块屏幕失败", error);
            stop(Component.translatable("screen.piq_fc_arcade.load_failed", safeMessage(error)));
        }
    }
    void leave() {
        if (isActive()) stop(Component.translatable("message.piq_fc_arcade.session_left"));
    }
    void update() {
        if (!isActive()) return;
        try {
            if (!isWorldAndBlockValid()) {
                stop(Component.translatable("message.piq_fc_arcade.control_lost"));
                return;
            }
            if(serverHosted()){
                if(minecraft.isPaused())releaseControls();else updateControls();
                hostedAudio.gain(minecraft.isPaused()||audioMuted?0:audioVolume());
                short[] samples;while((samples=hostedMedia.pollAudio())!=null)hostedAudio.offer(samples);
                var picture=hostedMedia.pollVideo();if(picture!=null&&shouldUploadFrame()){
                    for(int y=0;y<NesCore.HEIGHT;y++)for(int x=0;x<NesCore.WIDTH;x++){
                        int pixel=picture.abgr()[y*picture.height()/NesCore.HEIGHT*picture.width()+x*picture.width()/NesCore.WIDTH];int at=(y*NesCore.WIDTH+x)*4;
                        rgba[at]=(byte)pixel;rgba[at+1]=(byte)(pixel>>>8);rgba[at+2]=(byte)(pixel>>>16);rgba[at+3]=(byte)255;
                    }uploadRgba();
                }
                if(hostedMedia.error()!=null)throw new IllegalStateException(hostedMedia.error());return;
            }
            if(netplayState!=null){
                if(netplay==null){if(simulationEnabled&&System.nanoTime()>=nextSyncRequestNanos)startWorker(false);return;}
                if(netplay.error()!=null){FcArcadeMod.LOGGER.error("[PIQ Netplay] {}\n{}",netplay.error(),netplay.diagnostic());throw new IllegalStateException(netplay.error());}
                if(!workerReady&&netplay.ready()){workerReady=true;audioPlayer=new NesAudioPlayer();overlay(Component.literal("Netplay 实验 · 存档归属按开机选择；状态见运行环境"));}
                if(minecraft.isPaused())releaseControls();else updateControls();
                if(computeHost&&workerReady&&!homeReadySent&&connected()){FcNetwork.homeReady(sessionId,epoch);homeReadySent=true;}
                float gain=minecraft.isPaused()||audioMuted?0:audioVolume();
                cn.piq.fcarcade.netplay.NetplayProcess.Frame frame,last=null;
                while((frame=netplay.poll())!=null){
                    if(frame.rgba().length==rgba.length)last=frame;
                    if(audioPlayer!=null&&gain>0)audioPlayer.submit(frame.mono(),frame.mono().length,gain);
                }
                if(last!=null){localFrame=last.number();if(shouldUploadFrame()){System.arraycopy(last.rgba(),0,rgba,0,rgba.length);uploadRgba();}}
                return;
            }
            if (worker == null) return;
            boolean paused = minecraft.isPaused();
            // Minecraft changes its pause flag after rendering. Synchronize the
            // owner before any new edge can be accepted on the following loop.
            float volume = paused || audioMuted ? 0 : audioVolume();
            worker.configure(hasController(), paused, volume > 0);
            if (paused) releaseControls(); else updateControls();
            volume = paused || audioMuted ? 0 : audioVolume();
            worker.configure(hasController(), paused, volume > 0);
            if (!workerReady && worker.isReady()) {
                workerReady = true;
                audioPlayer = new NesAudioPlayer();
                if (requestSyncOnReady) requestLatestSnapshot();
                else overlay(Component.translatable("message.piq_fc_arcade.control_started"));
            }
            if(computeHost&&workerReady&&!homeReadySent&&connected()){FcNetwork.homeReady(sessionId,epoch);homeReadySent=true;}
            if (pendingSnapshotRequest && worker.requestSnapshot()) pendingSnapshotRequest = false;
            consumeResults(volume);
            if (worker != null) worker.presentControllerFrame();
            if (awaitingSnapshot && workerReady && System.nanoTime() >= nextSyncRequestNanos) requestLatestSnapshot();
            if(mediaPublisher!=null){mediaPublisher.tick();if(mediaPublisher.error()!=null)throw new IllegalStateException(mediaPublisher.error());}
        } catch (Throwable error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 方块屏幕客户端更新失败", error);
            stop(Component.translatable("message.piq_fc_arcade.run_failed", safeMessage(error)));
        }
    }
    void setSimulationEnabled(boolean enabled) {
        // Controllers retain their slot. This flag may be set before join.
        if (hasController()||computeHost) enabled = true;
        if (simulationEnabled == enabled) return;
        simulationEnabled = enabled;
        if(serverHosted())return;
        if (!isActive()) return;
        if (!enabled) {
            closeSimulation();
            awaitingSnapshot = true;
            silentUntilFrame = localFrame + 3;
        } else startWorker(mode == ArcadeMode.LOCKSTEP);
        uploadSyncFrame();
    }
    boolean isSimulationEnabled() { return simulationEnabled; }
    boolean isSimulationRunning() { return netplay!=null&&netplay.ready()||worker != null && worker.isReady(); }
    int controllerAnimationMask(long id,int port) {
        if(sessionId==id&&role.controllerIndex()==port&&hasController()&&workerReady&&!awaitingSnapshot&&(serverHosted()||netplay!=null))
            return keyboardAuthorized()&&inputCapture.armed()?inputMask:0;
        return sessionId==id&&role.controllerIndex()==port&&hasController()&&workerReady&&!awaitingSnapshot&&worker!=null
                ?worker.presentedControllerMask(port):-1;
    }
    String diagnosticSummary() {
        return "session=" + sessionId + " epoch=" + epoch + " role=" + role + " "
                + (netplay!=null?"RetroArch Netplay frames="+netplay.framesReceived():!simulationEnabled ? "spectator-paused" : worker == null ? "stopped" : worker.diagnostic())
                + (awaitingSnapshot ? " awaiting-snapshot" : "");
    }
    FcPerformanceView.Entry performance(boolean enabled) {
        if (worker != null) worker.performanceEnabled(enabled);
        if (!enabled) return null;
        var device = diagnosticDevice();
        if (device == null) return null;
        var p = worker == null ? null : worker.performance();
        return new FcPerformanceView.Entry(sessionId + ":" + epoch, device,
                netplayState!=null?"Netplay · "+(netplay==null?"准备游戏":netplay.status())+"（帧耗时未采样）":p != null ? p.state() : serverHosted() || playerMedia ? "本机接收音画" : !simulationEnabled ? "旁观模拟未启用" : "尚未启动本机核心",
                p == null ? null : p.sample(), p == null ? 0 : p.frame(), p == null ? -1 : p.pendingFrames(),
                p == null ? -1 : p.audioBuffers(), p == null ? -1 : p.audioMs());
    }
    ResourceLocation textureAt(BlockPos pos) { return isActiveAt(pos) ? textureId : null; }
    boolean isVisibleAt(BlockPos pos) {
        if (!isActiveAt(pos) || minecraft.player == null) return false;
        if(minecraft.level!=null&&minecraft.level.getBlockEntity(pos) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity)
            return cn.piq.fcarcade.client.cabinet.CabinetClientSettings.rules().visible(minecraft.player.distanceToSqr(pos.getCenter()));
        return minecraft.player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= (double) viewDistance * viewDistance;
    }
    String playerNames() { return playerNames; }
    String occupancyPlayerNames() {
        if (!playerNames.isBlank()) return playerNames;
        return hasController() && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
    }
    boolean hasOccupants() { return isActive(); }
    boolean isActive() { return sessionId >= 0 && arcadePos != null; }
    boolean hasController() { return isActive() && role.controllerIndex() >= 0; }
    /** Actual admitted runtime metadata; never reads device preferences or changes input ownership. */
    NetworkDiagnosticsView.Device diagnosticDevice() {
        if(!isActive()||minecraft.getConnection()!=keyboardConnection||!connected()||!isWorldAndBlockValid())return null;
        var actualMode=netplayState!=null?NetworkDiagnosticsView.Mode.NETPLAY:playerMedia?NetworkDiagnosticsView.Mode.PLAYER_MEDIA:serverHosted()?NetworkDiagnosticsView.Mode.SERVER_MEDIA:mode==ArcadeMode.LOCKSTEP
                ?NetworkDiagnosticsView.Mode.LOCAL_INPUT:NetworkDiagnosticsView.Mode.LEGACY_RELAY;
        var actualRole=keyboardPresent()?NetworkDiagnosticsView.Role.CONTROLLING:hasController()?NetworkDiagnosticsView.Role.SEATED
                :computeHost?NetworkDiagnosticsView.Role.COMPUTING:NetworkDiagnosticsView.Role.WATCHING;
        String machine=netplayState!=null?"FC Netplay 实验":homeRuntime?"FC / 小霸王":"FC 街机";
        return new NetworkDiagnosticsView.Device(machine+" @ "+arcadePos.toShortString(),actualMode,actualRole,
                minecraft.player.distanceToSqr(arcadePos.getCenter()));
    }
    String startScoreCalibration() {
        if (!hasController()) return "你当前没有在操作 FC 街机";
        if (!ScoreCalibrationSession.TARGET_ROM_SHA256.equalsIgnoreCase(romSha256)) return "当前游戏不是已识别的《公路赛车》测试 ROM";
        if (worker == null || !worker.calibrationStart()) return "模拟器尚未就绪或队列忙，请稍后重试";
        calibrationStarted = true;
        return "分数校准已开始；每次分数变化后输入 /fc-scorecal mark <画面分数>";
    }
    String markScoreCalibration(int visibleScore) {
        if (!calibrationStarted) return "请先输入 /fc-scorecal start";
        return worker != null && worker.calibrationMark(visibleScore)
                ? "已排队读取帧边界分数样本，完成后将显示记录结果" : "模拟器尚未就绪或队列忙，请稍后重试";
    }
    String finishScoreCalibration() {
        if (!calibrationStarted) return "当前没有正在进行的分数校准";
        return worker != null && worker.calibrationFinish(minecraft.gameDirectory.toPath())
                ? "正在后台生成校准报告，完成后将显示文件路径" : "后台队列忙，请稍后重试";
    }
    String cancelScoreCalibration() {
        if (worker != null && !worker.calibrationCancel()) return "后台队列忙，请稍后重试";
        calibrationStarted = false;
        return "分数校准已取消";
    }
    void enqueueFrame(ArcadeFramePayload payload) {
        if (!matches(payload.sessionId(), payload.epoch()) || awaitingSnapshot) return;
        if (!worker.enqueue(new ClientNesWorker.Input(payload.targetFrame(), payload.playerOneMask(), payload.playerTwoMask(),payload.zapperState()))) recoverQueueOverflow();
    }
    void applyHistory(ArcadeHistoryPayload payload) {
        if (!matches(payload.sessionId(), payload.epoch()) || awaitingSnapshot) return;
        if (payload.runs().size() > ClientNesWorker.MAX_INPUTS) { recoverQueueOverflow(); return; }
        var history = new ArrayList<ClientNesWorker.Input>(payload.runs().size());
        long target = payload.startFrame();
        for (var run : payload.runs()) {
            target += run.frames();
            history.add(new ClientNesWorker.Input(target, run.playerOneMask(), run.playerTwoMask(),run.zapperState()));
        }
        silentUntilFrame = target;
        if (!worker.history(payload.startFrame(), history)) recoverQueueOverflow();
    }
    void requestSnapshot(ArcadeSnapshotRequestPayload payload) {
        if (!matches(payload.sessionId(), payload.epoch()) || !snapshotOwner()) return;
        pendingSnapshotRequest = !worker.requestSnapshot();
    }
    void applySnapshot(ArcadeSnapshotPayload payload) {
        if (!matches(payload.sessionId(), payload.epoch()) || payload.frame() < 0
                || payload.frame() % LockstepState.FRAMES_PER_SERVER_TICK != 0) return;
        releaseControls();
        worker.snapshot(payload.frame(), payload.state(), false);
        calibrationStarted = false;
        awaitingSnapshot = false;
        pendingSnapshotRequest = false;
        localFrame = payload.frame();
        silentUntilFrame = localFrame + 3;
        if (audioPlayer != null) audioPlayer.clear();
        uploadSyncFrame();
    }
    void applyPersistentState(ArcadePersistentStatePayload payload) {
        if (!matches(payload.sessionId(), payload.epoch()) || !snapshotOwner()) return;
        releaseControls();
        worker.snapshot(0, payload.state(), true);
        if (audioPlayer != null) audioPlayer.clear();
    }
    private boolean matches(long id, int receivedEpoch) {
        return worker != null && mode == ArcadeMode.LOCKSTEP && id == sessionId && receivedEpoch == epoch;
    }
    private void startWorker(boolean needsSnapshot) {
        if(serverHosted())return;
        closeSimulation();
        if(netplayState!=null){
            awaitingSnapshot=false;workerReady=false;
            if(netplayState.jniTrial()&&!JniNetplayConsent.allowed()){
                overlay(Component.literal("本机未启用 JNI；输入 /gameconsole-jni-netplay 确认风险后，再开机/加入。"));
                nextSyncRequestNanos=System.nanoTime()+5_000_000_000L;return;
            }
            if(netplayState.ticket().equals(usedNetplayTicket)){
                if(!computeHost&&connected()&&System.nanoTime()>=nextSyncRequestNanos){FcNetwork.sendRomReady(sessionId,romSha256);nextSyncRequestNanos=System.nanoTime()+3_000_000_000L;}
                return;
            }
            usedNetplayTicket=netplayState.ticket();netplay=NetplayClient.start(netplayState);return;
        }
        String expectedSha = romSha256;
        var expectedVariant=variant;
        requestSyncOnReady = needsSnapshot && mode == ArcadeMode.LOCKSTEP;
        awaitingSnapshot = requestSyncOnReady;
        workerReady = false;
        localFrame = 0;
        silentUntilFrame = 3;
        worker = new ClientNesWorker(() -> {
            RomDescriptor rom = ClientRomLibrary.loadBySha256(expectedSha);
            if (rom == null) throw new IllegalStateException("本地缺少会话指定的 ROM");
            if (!rom.sha256().equalsIgnoreCase(expectedSha)) throw new IllegalStateException("本地 ROM 在加入后发生变化，请重新右键街机");
            if(cn.piq.fcarcade.session.NesCoreVariant.forRom(rom.header(),expectedVariant.isZapper())!=expectedVariant)
                throw new IllegalStateException("ROM 与会话核心不匹配，请客户端和服务器成套更新。");
            NesCore created = cn.piq.fcarcade.core.NesCores.create(expectedVariant);
            if(!created.stateNamespace().equals(expectedVariant.stateNamespace())){created.close();throw new IllegalStateException("Core state namespace mismatch");}
            try { created.loadRom(rom.bytes()); return created; }
            catch (Throwable error) { created.close(); throw error; }
        }, mode == ArcadeMode.LOCKSTEP, hasController(), expectedSha, awaitingSnapshot);
        if(mediaPublisher!=null)worker.mediaTap(mediaPublisher);
    }
    private void consumeResults(float volume) {
        ClientNesWorker current = worker;
        var delivery = current.drain();
        if (delivery.generation() != current.generation()) return;
        for (var event : delivery.events()) {
            if (worker != current || delivery.generation() != current.generation()) return;
            switch (event.kind()) {
                case "digest" -> {
                    if (connected()) FcNetwork.sendDigest(new ArcadeDigestPayload(sessionId, epoch, event.frame(), event.number()));
                }
                case "score" -> {
                    if (role == ArcadeRole.PLAYER_ONE && connected()) FcNetwork.sendScore(new ArcadeScorePayload(sessionId, (int) event.number()));
                }
                case "snapshot" -> {
                    if (snapshotOwner() && connected()) FcNetwork.uploadSnapshot(new ArcadeSnapshotUploadPayload(sessionId, epoch, event.frame(), event.bytes()));
                }
                case "resync" -> { recoverQueueOverflow(); return; }
                case "sync" -> overlay(Component.translatable("message.piq_fc_arcade.synchronizing"));
                case "synced" -> overlay(Component.translatable("message.piq_fc_arcade.synchronized"));
                case "loaded" -> overlay(Component.translatable("message.piq_fc_arcade.save_loaded"));
                case "notice" -> {
                    if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(event.message()), false);
                }
                case "error" -> {
                    stop(Component.translatable("message.piq_fc_arcade.run_failed", event.message()));
                    return;
                }
                default -> { }
            }
        }
        if (volume > 0 && audioPlayer != null && !awaitingSnapshot) {
            for (var chunk : delivery.audio()) {
                if (chunk.generation() != current.generation()) continue;
                float[] samples = chunk.samples();
                audioPlayer.submit(samples, samples.length, volume);
            }
        }
        var picture = delivery.picture();
        if (picture == null || picture.generation() != current.generation()) return;
        localFrame = picture.frame();
        silentUntilFrame = picture.silentUntil();
        if (!shouldUploadFrame()) return;
        if (awaitingSnapshot || localFrame < silentUntilFrame) {
            SyncScreenPainter.paint(rgba, localFrame, Math.max(localFrame + 1, silentUntilFrame));
        } else {
            byte[] pixels = picture.rgba();
            System.arraycopy(pixels, 0, rgba, 0, rgba.length);
        }
        uploadRgba();
    }
    private void recoverQueueOverflow() {
        // Never skip authoritative input and continue with a divergent core.
        startWorker(true);
        uploadSyncFrame();
    }
    private void requestLatestSnapshot() {
        if (!connected() || worker == null || !worker.isReady()) return;
        FcNetwork.sendRomReady(sessionId, romSha256);
        nextSyncRequestNanos = System.nanoTime() + 3_000_000_000L;
        requestSyncOnReady = false;
    }
    void suspendInput() { releaseControls(); }
    /** Called after vanilla/NeoForge update KeyMapping for every key or mouse edge. */
    void captureInputEvent() {
        if (!isActive() || worker == null&&netplay==null&&!serverHosted()) return;
        try {
            if (!isWorldAndBlockValid()) { releaseControls(); return; }
            boolean paused = minecraft.isPaused();
            if(worker!=null)worker.configure(hasController(), paused, !paused && !audioMuted && audioVolume() > 0);
            updateControls();
        } catch (Throwable error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 按键事件处理失败", error);
            stop(Component.translatable("message.piq_fc_arcade.run_failed", safeMessage(error)));
        }
    }
    private void updateControls() {
        if(!hasController()){releaseControls();cn.piq.retro.client.KeyboardInput.release(this);cn.piq.retro.client.GamepadInput.release(this);return;}
        refreshKeyboardCapture();
        if (minecraft.screen != null || !minecraft.isWindowActive() || minecraft.isPaused()
                || awaitingSnapshot || !hasController()) { releaseControls(); return; }
        int controllerIndex = controllerInputPort();
        int nextMask = 0;
        if (controllerIndex >= 0) {
            if (ArcadeKeyMappings.UP.isDown()) nextMask |= NesButton.UP.mask();
            if (ArcadeKeyMappings.DOWN.isDown()) nextMask |= NesButton.DOWN.mask();
            if (ArcadeKeyMappings.LEFT.isDown()) nextMask |= NesButton.LEFT.mask();
            if (ArcadeKeyMappings.RIGHT.isDown()) nextMask |= NesButton.RIGHT.mask();
            if (ArcadeKeyMappings.A.isDown() || ArcadeKeyMappings.A_ALT.isDown()) nextMask |= NesButton.A.mask();
            if (ArcadeKeyMappings.B.isDown() || ArcadeKeyMappings.B_ALT.isDown()) nextMask |= NesButton.B.mask();
            if (ArcadeKeyMappings.START.isDown()) nextMask |= NesButton.START.mask();
            if (ArcadeKeyMappings.SELECT.isDown() || ArcadeKeyMappings.SELECT_ALT.isDown()) nextMask |= NesButton.SELECT.mask();
        }
        var keyboard=cn.piq.retro.client.KeyboardInput.poll(this,nextMask,true);
        if(!keyboard.enabled()||!keyboard.armed()){releaseControls();return;}
        zapperControlsEnabled=true;
        // The server admits gun-keyboard fallback only if P1 is empty, or through
        // this person's own formal P1 lease. Holding a gun never creates a seat.
        if(homeRuntime&&(controllerIndex<0||!heldController()&&!heldZapper())){cn.piq.retro.client.GamepadInput.pause(this);releaseControllerButtons();return;}
        nextMask = cn.piq.retro.client.GamepadInput.mix(this,
                cn.piq.retro.client.GamepadInput.ProfileKind.NES, keyboard.mask(), keyboard.enabled()&&keyboard.armed());
        int sampled = inputCapture.sample(nextMask
                | (cn.piq.retro.client.KeyboardInput.down(this,ArcadeKeyMappings.RESET) ? 256 : 0)
                | (cn.piq.retro.client.KeyboardInput.down(this,ArcadeKeyMappings.MUTE) ? 512 : 0));
        nextMask = sampled & 255;
        if(worker!=null)worker.setControllerPresentationEnabled(inputCapture.armed());
        if (nextMask != inputMask) {
            inputMask = nextMask;
            if (controllerIndex >= 0) {
                if (mode == ArcadeMode.LOCKSTEP) {
                    if (connected()) sendInput(new ArcadeInputPayload(sessionId, epoch, inputSequence++, inputMask));
                } else worker.input(controllerIndex, inputMask);
            }
        }
        boolean resetDown = (sampled & 256) != 0;
        if (!homeRuntime&&role == ArcadeRole.PLAYER_ONE && resetDown && !resetWasDown) {
            if (mode == ArcadeMode.LOCKSTEP) {
                releaseControls();
                if (connected()) FcNetwork.requestReset(new ArcadeResetPayload(sessionId, epoch));
            } else {
                releaseControls();
                worker.reset();
                if (audioPlayer != null) audioPlayer.clear();
                inputMask = 0;
            }
        }
        resetWasDown = resetDown;
        boolean muteDown = (sampled & 512) != 0;
        if (muteDown && !muteWasDown) {
            audioMuted = !audioMuted;
            if (audioPlayer != null) audioPlayer.clear();
            overlay(Component.translatable(audioMuted ? "screen.piq_fc_arcade.audio_muted" : "message.piq_fc_arcade.audio_restored"));
        }
        muteWasDown = muteDown;
    }
    private void releaseControls() {
        zapperControlsEnabled=false;
        cn.piq.retro.client.KeyboardInput.pause(this);
        cn.piq.retro.client.GamepadInput.pause(this);
        releaseControllerButtons();
    }
    private int controllerInputPort(){return homeRuntime&&variant.isZapper()&&role==ArcadeRole.PLAYER_TWO?0:role.controllerIndex();}
    private void releaseControllerButtons(){
        if(netplay!=null)netplay.input(0);
        boolean forceRelease = inputCapture.suspend();
        inputMask = 0;
        if (worker != null) worker.setControllerPresentationEnabled(false);
        try {
            int controllerIndex = controllerInputPort();
            if (forceRelease && controllerIndex >= 0) {
                // Local neutralization must succeed even if sending on a closing channel fails.
                if (worker != null) worker.clearInput(controllerIndex);
                if (mode == ArcadeMode.LOCKSTEP) {
                    if (connected()) sendInput(new ArcadeInputPayload(sessionId, epoch, inputSequence++, 0, true));
                }
            }
        } catch (RuntimeException error) {
            FcArcadeMod.LOGGER.debug("[PIQ FC] 连接已关闭，按键仅在本地释放", error);
        } finally {
            resetWasDown = false;
            muteWasDown = false;
        }
    }
    private void sendInput(ArcadeInputPayload input){if(netplay!=null&&!variant.isZapper()){netplay.input(input.buttonMask());return;}if(homeRuntime){if(controllerLease!=null)FcNetwork.sendHomeInput(new ArcadeHomeInputPayload(controllerLease,input));}else FcNetwork.sendInput(input);}
    private boolean connected() { return minecraft.getConnection() != null&&minecraft.getConnection()==keyboardConnection&&minecraft.getConnection().getConnection().isConnected(); }
    boolean authorizedZapper(cn.piq.fcarcade.home.ZapperBinding binding){
        return binding!=null&&variant.isZapper()&&role.controllerIndex()>=0
                &&(!homeRuntime||role==ArcadeRole.PLAYER_ONE||binding.lease().equals(controllerLease))
                &&sessionId==binding.sessionId()&&epoch==binding.epoch()&&arcadePos!=null&&arcadePos.equals(binding.tvPos())
                &&dimension!=null&&dimension.location().equals(binding.dimension())&&workerReady&&zapperControlsEnabled&&keyboardAuthorized()
                &&minecraft.screen==null&&minecraft.isWindowActive()&&!minecraft.isPaused();
    }
    void refreshKeyboardCapture(){
        if(!keyboardPresent()){keyboardIdentity=null;cn.piq.retro.client.KeyboardInput.release(this);cn.piq.retro.client.GamepadInput.pause(this);return;}
        var next=heldKeyboardIdentity();
        if(!java.util.Objects.equals(next,keyboardIdentity)){keyboardIdentity=next;releaseControls();}
        cn.piq.retro.client.KeyboardInput.attach(this,cn.piq.retro.client.KeyboardConfig.Profile.NES,ArcadeKeyMappings::legacyKeys,
                this::keyboardPresent,this::keyboardAuthorized,this::releaseControls,this::captureInputEvent);
    }
    private boolean startupControlsShown;
    void showStartupControls(){
        if(startupControlsShown||minecraft.player==null)return;
        startupControlsShown=true;
        var config=cn.piq.retro.client.KeyboardInput.settingsSnapshot().config();
        minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal("[FC] 拿起手柄后，按 "+cn.piq.retro.client.KeyboardInput.keyName(config.toggleKey())
                +" 锁定/解除人物移动；按 "+cn.piq.retro.client.KeyboardInput.keyName(config.settingsKey())+" 打开按键设置。"),false);
    }
    private ControllerCapturePolicy.Held heldKeyboardIdentity(){
        if(variant.isZapper()&&heldZapper()){
            var gun=cn.piq.fcarcade.home.ZapperData.binding(minecraft.player.getMainHandItem());
            return new ControllerCapturePolicy.Held(gun.lease(),1,0,true);
        }
        if(!homeRuntime)return null;
        int hand=controllerLease!=null&&controllerLease.equals(cn.piq.fcarcade.home.HomeControllerData.leaseId(minecraft.player.getMainHandItem()))?0:1;
        return new ControllerCapturePolicy.Held(controllerLease,role.controllerIndex(),hand,false);
    }
    private boolean keyboardPresent(){return hasController()&&minecraft.getConnection()==keyboardConnection
            &&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection().isConnected()&&minecraft.player!=null&&minecraft.player.isAlive()&&!minecraft.player.isSpectator()&&isWorldAndBlockValid()
            &&(variant.isZapper()?heldZapper()||homeRuntime&&role==ArcadeRole.PLAYER_ONE&&heldController():!homeRuntime||heldController());}
    private boolean keyboardAuthorized(){return keyboardPresent()&&(worker!=null||(serverHosted()||netplay!=null)&&workerReady)&&!awaitingSnapshot;}
    private boolean heldController(){
        if(controllerLease==null||minecraft.player==null)return false;
        for(var stack:java.util.List.of(minecraft.player.getMainHandItem(),minecraft.player.getOffhandItem()))
            if(stack.getCount()==1&&controllerLease.equals(cn.piq.fcarcade.home.HomeControllerData.leaseId(stack))&&cn.piq.fcarcade.home.HomeControllerData.port(stack)==role.controllerIndex()
                    &&ControllerCapture.unique(minecraft.player,stack,controllerLease,cn.piq.fcarcade.home.HomeControllerData::leaseId))return true;
        return false;
    }
    private boolean heldZapper(){var b=cn.piq.fcarcade.home.ZapperData.binding(minecraft.player.getMainHandItem());return b!=null
            &&minecraft.player.getMainHandItem().getItem() instanceof cn.piq.fcarcade.home.HomeZapperItem
            &&b.sessionId()==sessionId&&b.epoch()==epoch&&b.tvPos().equals(arcadePos)&&b.dimension().equals(dimension.location())
            &&(!homeRuntime||role==ArcadeRole.PLAYER_ONE||b.lease().equals(controllerLease))
            &&ControllerCapture.unique(minecraft.player,minecraft.player.getMainHandItem(),b.lease(),stack->{var value=cn.piq.fcarcade.home.ZapperData.binding(stack);return value==null?null:value.lease();});}
    boolean visualZapperTrigger(cn.piq.fcarcade.home.ZapperBinding b){
        if(b==null||!connected()||!variant.isZapper()
                ||sessionId!=b.sessionId()||epoch!=b.epoch()||arcadePos==null||!arcadePos.equals(b.tvPos())
                ||dimension==null||!dimension.location().equals(b.dimension())||!workerReady||awaitingSnapshot)return false;
        // Media receivers have no core. Only this client's exact live gun grant may
        // use its already-captured trigger; another player's gun stays neutral.
        if(serverHosted()||netplay!=null)return authorizedZapper(b)&&heldZapper()&&inputCapture.armed()
                &&cn.piq.fcarcade.client.zapper.ZapperClient.sampledTrigger(b);
        return worker!=null&&worker.appliedZapperTrigger();
    }
    private boolean isWorldAndBlockValid() {
        if (minecraft.level == null || minecraft.player == null
                || dimension != minecraft.level.dimension() || arcadePos == null) return false;
        // The server owns appliance/chunk validity. A distant computing host may
        // have no local display chunk while another player keeps that chunk loaded.
        if(computeHost)return connected();
        if(homeRuntime&&role.controllerIndex()>=0){
            var console=cn.piq.fcarcade.home.HomeHardware.connectedConsole(minecraft.level,arcadePos);
            if(console==null)return false;
            double consoleDistance=minecraft.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(console.getBlockPos()));
            return variant.isZapper()&&role==ArcadeRole.PLAYER_TWO
                    ?Math.min(consoleDistance,minecraft.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(arcadePos)))<=64
                    :cn.piq.fcarcade.home.HomeRuntimeAuthority.controllerInRange(consoleDistance);
        }
        if (!(minecraft.level.getBlockState(arcadePos).getBlock() instanceof FcArcadeBlock)) return false;
        double max = role == ArcadeRole.SPECTATOR ? Math.pow(Math.max(viewDistance, audioDistance), 2) : 64;
        if(role==ArcadeRole.SPECTATOR&&minecraft.level.getBlockEntity(arcadePos) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity)
            max=Math.pow(cn.piq.fcarcade.client.cabinet.CabinetClientSettings.rules().range(),2);
        return minecraft.player.distanceToSqr(arcadePos.getX() + 0.5D, arcadePos.getY() + 0.5D,
                arcadePos.getZ() + 0.5D) <= max;
    }
    private boolean isActiveAt(BlockPos pos) {
        return isActive() && arcadePos.equals(pos) && minecraft.level != null && dimension == minecraft.level.dimension();
    }
    private void uploadSyncFrame() {
        if (texture == null) return;
        if (!simulationEnabled) SyncScreenPainter.paintPaused(rgba);
        else SyncScreenPainter.paint(rgba, localFrame, Math.max(localFrame + 3, silentUntilFrame));
        uploadRgba();
    }
    private boolean shouldUploadFrame() {
        return FrameUploadPolicy.shouldUpload(hasController(), isVisibleAt(arcadePos), presentationSequence++);
    }
    private void uploadRgba() {
        if (texture == null) return;
        NativeImage image = texture.getPixels();
        if (image == null) throw new IllegalStateException("方块屏幕纹理缓冲区不可用");
        var pixels = MemoryUtil.memByteBuffer(image.pixels, rgba.length);
        pixels.position(0);
        pixels.put(rgba);
        texture.upload();
    }
    private void restartLockstep(int nextEpoch) {
        releaseControls();
        if(mediaPublisher!=null){mediaPublisher.close();mediaPublisher=null;if(worker!=null)worker.mediaTap(null);}
        epoch = nextEpoch;
        inputSequence = 0;
        homeInputSequences.clear();
        homeReadySent=false;
        localFrame = 0;
        presentationSequence = 0;
        silentUntilFrame = 0;
        calibrationStarted = false;
        pendingSnapshotRequest = false;
        awaitingSnapshot = false;
        if (audioPlayer != null) audioPlayer.clear();
        if(serverHosted())startHostedMedia();else if (worker != null) worker.reset();
        else if (simulationEnabled) startWorker(false);
        uploadSyncFrame();
    }
    private float audioVolume() {
        float configured = minecraft.options.getSoundSourceVolume(SoundSource.MASTER)
                * minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) * audioVolumePercent / 100.0F;
        if (minecraft.player == null || arcadePos == null) return 0;
        double distance = Math.sqrt(minecraft.player.distanceToSqr(arcadePos.getX() + 0.5D,
                arcadePos.getY() + 0.5D, arcadePos.getZ() + 0.5D));
        return configured * SpatialAudio.distanceGain(distance, 2.0D, audioDistance)
                * (homeRuntime?cn.piq.fcarcade.home.HomeApplianceService.audioGain(minecraft.level,arcadePos):1);
    }
    private void stop(Component message) { closeResources(); overlay(message); }
    private void closeSimulation() {
        try { releaseControls(); }
        finally {
            if(netplay!=null){NetplayClient.stop(netplay);netplay=null;}
            if (worker != null) { worker.mediaTap(null); worker.close(); worker = null; }
            if(hostedMedia!=null){hostedMedia.close();hostedMedia=null;}if(hostedAudio!=null){hostedAudio.close();hostedAudio=null;}
            if (audioPlayer != null) {
                try { audioPlayer.close(); }
                catch (RuntimeException error) { FcArcadeMod.LOGGER.debug("[PIQ FC] 音频关闭失败", error); }
                finally { audioPlayer = null; }
            }
            workerReady = false;
            pendingSnapshotRequest = false;
            calibrationStarted = false;
        }
    }
    private void closeResources() {
        if(mediaPublisher!=null){mediaPublisher.close();mediaPublisher=null;}
        try { closeSimulation(); }
        finally {
            cn.piq.retro.client.GamepadInput.release(this);
            cn.piq.retro.client.KeyboardInput.release(this);keyboardConnection=null;
            try {
                if (textureId != null) minecraft.getTextureManager().release(textureId);
                else if (texture != null) texture.close();
            } catch (RuntimeException error) { FcArcadeMod.LOGGER.debug("[PIQ FC] 纹理关闭失败", error); }
            finally {
                textureId = null; texture = null; dimension = null; arcadePos = null;
                audioMuted = false; role = ArcadeRole.SPECTATOR; mode = ArcadeMode.STREAM;
                homeRuntime=false;computeHost=false;playerMedia=false;controllerLease=null;controlRevision=0;keyboardIdentity=null;
                homeReadySent=false;
                netplayState=null;usedNetplayTicket=null;
                hostedSource=null;hostedToken=null;hostedOperator=false;
                sessionId = -1; epoch = 0; inputSequence = 0; homeInputSequences.clear(); localFrame = 0;
                presentationSequence = 0; silentUntilFrame = 0; romSha256 = null;
                awaitingSnapshot = false; requestSyncOnReady = false;
            }
        }
    }
    private void setRole(ArcadeRole nextRole) {
        if (role == nextRole) return;
        releaseControls();
        role = nextRole;
        if (hasController()) setSimulationEnabled(true);
        else {cn.piq.retro.client.GamepadInput.release(this);cn.piq.retro.client.KeyboardInput.release(this);}
    }
    private void overlay(Component message) { cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("FC", message); }
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
