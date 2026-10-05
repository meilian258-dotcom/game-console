// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfcarcade.audio.SfcAudioPlayer;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcJoinNetwork;
import cn.piq.sfchome.net.SfcRepairNetwork;
import cn.piq.sfchome.server.SfcRepairLedger;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.LoggerFactory;

/** One thread owns one core. Server batches are paced at the core's actual FPS, not 20 Hz. */
final class SfcPlayback implements AutoCloseable {
    /** Platform effects are isolated so the actual worker can be exercised in headless clients. */
    interface Audio extends AutoCloseable {
        void submit(short[] pcm,int length,float gain);
        /** Discard pre-stall device/PCM backlog once, not once per emulated frame. */
        default void discardQueued() {}
        /** Optional output-device failure; not an emulator or media transport failure. */
        default String failureMessage(){return null;}
        @Override void close();
    }
    /** Thin owned-process seam; production still uses the shared Netplay lifecycle. */
    interface Netplay extends AutoCloseable {
        void start(); boolean ready(); void activate(); boolean nativeSlotHeld();
        void input(int mask); cn.piq.fcarcade.netplay.NetplayProcess.Frame poll();
        String error(); String diagnostic(); @Override void close();
    }
    interface Host {
        void execute(Runnable action);
        boolean isCurrent(SfcPlayback playback);
        void ready(SfcPlayback playback,SfcHomeNetwork.Ready ready);
        void captured(SfcPlayback playback,SfcJoinNetwork.Capture request,byte[] bytes,String sha);
        void applied(SfcPlayback playback,SfcJoinNetwork.Capture request,String sha,boolean success);
        Audio openAudio();
        default Netplay openNetplay(SfcHomeNetwork.NetplayStart grant,byte[] rom,boolean waitForActivation){throw new UnsupportedOperationException("Netplay host not configured");}
        void backup(SfcPlayback playback,SfcCore core,int frame) throws Exception;
        /** Local disk result: may arrive after leaving; never sends a network packet. */
        default void backupNotice(SfcPlayback playback,String message){}
        /** Arrays are worker-owned and valid only during this callback; never mutate them. */
        default void observedFrame(int nextFrame,int width,int height,byte[] rgba,short[] pcm,int pcmLength){}
        /** Optional media-only tap. PCM length is stereo frames; arrays are borrowed until return. */
        default void mediaFrame(SfcPlayback playback,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){}
        default void mediaClosed(SfcPlayback playback){}
        default boolean consistencyChecks(){return false;}
        default boolean readOnlyObserver(){return false;}
        default void digest(SfcPlayback playback,int frame,String sha){}
        default void repairedState(SfcPlayback playback,SfcRepairNetwork.Key key,String sha,boolean success){}
        default void repairedFrames(SfcPlayback playback,SfcRepairNetwork.Key key,boolean success){}
        default void synchronizationFault(SfcPlayback playback,int frame){}
    }
    private static final class MinecraftHost implements Host {
        // Capture on the MC thread with the assignment, never bind a late worker to a new server.
        private final net.minecraft.network.Connection connection=Minecraft.getInstance().getConnection().getConnection();
        public void execute(Runnable action){Minecraft.getInstance().execute(action);}
        public boolean isCurrent(SfcPlayback playback){return SfcHomeClient.isCurrent(playback);}
        public void ready(SfcPlayback playback,SfcHomeNetwork.Ready ready){PacketDistributor.sendToServer(new SfcJoinNetwork.ControllerReady(playback.session.controllerLease(),ready));}
        public void captured(SfcPlayback playback,SfcJoinNetwork.Capture request,byte[] bytes,String sha){SfcJoinClient.captured(playback,request,bytes,sha);}
        public void applied(SfcPlayback playback,SfcJoinNetwork.Capture request,String sha,boolean success){SfcJoinClient.applied(playback,request,sha,success);}
        public Audio openAudio(){
            return new Audio(){
                private SfcAudioPlayer player=new SfcAudioPlayer();
                public void submit(short[] pcm,int length,float gain){if(player!=null)player.submit(pcm,length,gain);}
                public String failureMessage(){return player==null?null:player.failureMessage();}
                public void discardQueued(){var previous=player;player=null;if(previous!=null)previous.close();player=new SfcAudioPlayer();}
                public void close(){var previous=player;player=null;if(previous!=null)previous.close();}
            };
        }
        public Netplay openNetplay(SfcHomeNetwork.NetplayStart grant,byte[] rom,boolean waitForActivation){
            var authority=new cn.piq.fcarcade.netplay.NetplayProcess.Grant(grant.wire(),grant.ticket(),grant.session().executionHost(),true);
            var run=new cn.piq.fcarcade.netplay.NetplayProcess(authority,()->rom,
                    c->cn.piq.fcarcade.netplay.NetplayNetwork.upstream(connection,c),
                    cn.piq.sfchome.core.SfcNetplayProfile.profile(),java.util.Map::of,false,false,waitForActivation);
            try{cn.piq.fcarcade.netplay.NetplayNetwork.bind(connection,run);}
            catch(RuntimeException|Error failure){run.close();throw failure;}
            return new Netplay(){
                public void start(){run.start();} public boolean ready(){return run.ready();}
                public void activate(){run.activate();} public boolean nativeSlotHeld(){return run.nativeSlotHeld();}
                public void input(int mask){run.inputRetroPad(mask);} public cn.piq.fcarcade.netplay.NetplayProcess.Frame poll(){return run.poll();}
                public String error(){return run.error();} public String diagnostic(){return run.diagnostic();}
                public void close(){cn.piq.fcarcade.netplay.NetplayNetwork.unbind(connection,run);run.close();}
            };
        }
        public void backup(SfcPlayback playback,SfcCore core,int frame)throws Exception{
            SfcClientFiles.snapshot(Minecraft.getInstance().gameDirectory.toPath(),playback.session.romSha(),playback.backupSession,core.saveState(),core.saveSram(),frame);
        }
        public void backupNotice(SfcPlayback playback,String message){
            // The worker finishes its final snapshot after closeLocal has detached it.
            // Do not drop a genuine disk failure just because this is no longer current.
            execute(()->cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(message));
        }
        public void mediaFrame(SfcPlayback playback,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){
            SfcWatchPublisher.frame(playback,width,height,stride,aspect,rgba,pcm,stereoFrames);
        }
        public void mediaClosed(SfcPlayback playback){SfcWatchPublisher.closed(playback);}
        public boolean consistencyChecks(){return true;}
        public void digest(SfcPlayback playback,int frame,String sha){var s=playback.session;PacketDistributor.sendToServer(new SfcRepairNetwork.Digest(new SfcRepairNetwork.Key(s.sessionId(),s.epoch(),s.controllerLease(),SfcRepairNetwork.DIGEST_TOKEN,frame),sha));}
        public void repairedState(SfcPlayback playback,SfcRepairNetwork.Key key,String sha,boolean success){SfcRepairClient.restored(playback,key,sha,success);}
        public void repairedFrames(SfcPlayback playback,SfcRepairNetwork.Key key,boolean success){SfcRepairClient.done(playback,key,success);}
        public void synchronizationFault(SfcPlayback playback,int frame){var s=playback.session;PacketDistributor.sendToServer(new SfcRepairNetwork.Fault(new SfcRepairNetwork.Key(s.sessionId(),s.epoch(),s.controllerLease(),SfcRepairNetwork.DIGEST_TOKEN,frame)));}
    }
    private final Host host;
    private final Runnable receivedFrameMeter;
    private final SfcCoreLease lease;
    private final SfcStartupProgress startup;
    final SfcHomeNetwork.Session session;
    private final ArrayBlockingQueue<FrameBatch> input=new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<cn.piq.fcarcade.cabinet.CabinetMediaPacket> mediaInput=new ArrayBlockingQueue<>(24);
    private volatile boolean resetMedia;
    private final SfcMediaFreshness mediaFreshness=new SfcMediaFreshness();
    private SfcMediaFreshness.Phase mediaPhase=SfcMediaFreshness.Phase.WAITING;
    private final AtomicReference<String> mediaNotice=new AtomicReference<>(),audioNotice=new AtomicReference<>();
    private boolean audioWarningShown;
    private final SfcCheckpoints checkpoints=new SfcCheckpoints();
    private final java.util.UUID backupSession=java.util.UUID.randomUUID();
    private final SfcBackupStatus backupStatus=new SfcBackupStatus();
    private volatile SfcRepairNetwork.Key repair;
    private volatile RepairRestore repairRestore;
    private volatile boolean repairPaused;
    private volatile int repairResume=-1;
    private volatile int observedFrame;
    private volatile boolean faultRequested;
    private volatile long faultBegan;
    private final AtomicReference<Picture> pending=new AtomicReference<>();
    // Reuses the existing private render copy; enables a new observer during a paused P2 join.
    private volatile Picture watchPicture;
    private boolean mediaFailed;
    private final byte[] rom;
    private final Thread thread;
    private volatile Netplay netplay;
    private final SfcNetplayStartGate netplayStart;
    boolean nativeSlotHeld(){var active=netplay;return active!=null&&active.nativeSlotHeld();}
    private volatile int netplayMask;
    void netplayInput(int mask){netplayMask=netplayStart!=null&&netplayStart.active()?mask:0;var run=netplay;if(run!=null)run.input(netplayMask);}
    boolean activateNetplay(SfcHomeNetwork.NetplayActivated message){
        var run=netplay;return running&&run!=null&&netplayStart!=null&&netplayStart.activate(message,run::activate);
    }
    private volatile boolean running=true;
    private volatile boolean started;
    private volatile float gain;
    private volatile String error;
    private volatile SfcJoinNetwork.Capture capture;
    private volatile Restore restore;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private double aspect=4.0/3.0;
    SfcPlayback(SfcHomeNetwork.Session session, byte[] rom,SfcStartupProgress startup) {
        this(session,rom,startup,new MinecraftHost());
    }
    SfcPlayback(SfcHomeNetwork.Session session,byte[] rom,SfcStartupProgress startup,SfcHomeNetwork.NetplayStart grant){
        this(session,rom,startup,new MinecraftHost(),grant);
    }
    SfcPlayback(SfcHomeNetwork.Session session,byte[] rom,SfcStartupProgress startup,Host host) {
        this(session,rom,startup,host,null);
    }
    SfcPlayback(SfcHomeNetwork.Session session,byte[] rom,SfcStartupProgress startup,Host host,SfcHomeNetwork.NetplayStart grant) {
        // The loader transfers its private byte[]; avoid cloning 32 MiB on the render thread.
        this.session=session;this.rom=java.util.Objects.requireNonNull(rom);this.startup=startup;this.host=java.util.Objects.requireNonNull(host);
        netplayStart=grant==null?null:new SfcNetplayStartGate(grant);
        receivedFrameMeter=cn.piq.fcarcade.network.ModTrafficProbe.videoMeter(session.mediaSource());
        if(session.syncMode()==3){
            if(grant==null||grant.session()!=session)throw new IllegalArgumentException("Missing Netplay authority");
            lease=SfcCoreLease.acquire();
            try{thread=Thread.ofPlatform().daemon(true).name("PIQ-SFC-Netplay-"+session.sessionId()).start(()->runNetplay(grant));}
            catch(Throwable failure){lease.close();throw failure;}return;
        }
        if(session.receivesMedia()){
            lease=null;
            thread=Thread.ofPlatform().daemon(true).name("PIQ-SFC-Home-Receiver-"+session.sessionId()).start(this::runHosted);
            return;
        }
        if(host.readOnlyObserver())lease=SfcCoreLease.acquireObserver();
        else lease=SfcCoreLease.acquire();
        try { thread=Thread.ofPlatform().daemon(true).name("PIQ-SFC-Home-"+session.sessionId()).start(this::run); }
        catch(Throwable failure) { lease.close();throw failure; }
    }
    private void runNetplay(SfcHomeNetwork.NetplayStart grant){
        Netplay run=null;Audio audio=null;
        try{
            run=host.openNetplay(grant,rom,session.executionHost());netplay=run;
            if(!running)return;run.start();
            while(running){
                if(run.error()!=null)throw new IllegalStateException(run.error()+"\n"+run.diagnostic());
                if(run.ready()&&netplayStart.prepared()){
                    startup.enter(SfcStartupProgress.Stage.READY);
                    host.execute(()->{if(running&&host.isCurrent(this))host.ready(this,new SfcHomeNetwork.Ready(session.sessionId(),session.epoch(),session.romSha(),SfcHomeNetwork.CORE_BUILD,60,session.romSha()));});
                }
                if(!netplayStart.active()){Thread.sleep(2);continue;}
                if(audio==null)audio=host.openAudio();
                run.input(netplayMask);
                var frame=run.poll();if(frame==null){Thread.sleep(2);continue;}
                if(frame.rgba().length>0){
                    var picture=new Picture(frame.width(),frame.height(),frame.width()*4,frame.aspect(),frame.rgba());pending.set(picture);watchPicture=picture;
                    host.mediaFrame(this,frame.width(),frame.height(),frame.width()*4,frame.aspect(),frame.rgba(),frame.stereo(),frame.stereo().length/2);
                    started=true;startup.enter(SfcStartupProgress.Stage.RUNNING);
                }
                if(frame.stereo().length>0)audio.submit(frame.stereo(),frame.stereo().length/2,gain);observeAudioFailure(audio);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception|LinkageError failure){if(running){LoggerFactory.getLogger("PIQ SFC Home").warn("Netplay stopped",failure);cn.piq.fcarcade.client.ui.DeviceNotices.record("SFC",run==null?"Netplay 启动失败":run.diagnostic(),failure);error="SFC Netplay 已停止，请查看运行环境诊断";}}
        finally{running=false;netplayStart.close();try{if(run!=null)run.close();}finally{netplay=null;try{if(audio!=null)audio.close();}finally{lease.close();}}}
    }
    boolean matches(long id,int epoch) { return session.sessionId()==id && session.epoch()==epoch; }
    void offerMedia(cn.piq.sfchome.net.SfcHostedNetwork.Stream packet){
        if(!running||!session.receivesMedia()||!matches(packet.session(),packet.epoch())||!session.controllerLease().equals(packet.recipient()))return;
        var part=packet.packet();if(!session.mediaSource().equals(part.room())||!session.mediaStream().equals(part.hostMember()))return;
        mediaInput.offer(part); // Bounded loss; the assembler discards partial video rather than growing a backlog.
    }
    private void runHosted(){
        var assembler=new cn.piq.fcarcade.client.cabinet.CabinetMediaAssembler();
        Audio audio=null;
        try{
            audio=host.openAudio();
            startup.enter(SfcStartupProgress.Stage.READY);
            host.execute(()->{if(running&&host.isCurrent(this))host.ready(this,new SfcHomeNetwork.Ready(session.sessionId(),session.epoch(),session.romSha(),SfcHomeNetwork.CORE_BUILD,60,session.romSha()));});
            while(running){
                if(resetMedia){resetMedia=false;audio.close();audio=host.openAudio();assembler=new cn.piq.fcarcade.client.cabinet.CabinetMediaAssembler();}
                checkMediaFreshness();observeAudioFailure(audio);
                var part=mediaInput.poll(250,TimeUnit.MILLISECONDS);
                checkMediaFreshness();observeAudioFailure(audio);
                if(part==null)continue;
                var complete=assembler.accept(part,System.nanoTime());if(complete==null)continue;
                var h=complete.header();
                if(h.kind()==1){short[] pcm=cn.piq.fcarcade.cabinet.CabinetMediaCodec.decodePcm(complete.bytes());audio.submit(pcm,pcm.length/2,gain);continue;}
                int[] abgr=cn.piq.fcarcade.cabinet.CabinetMediaCodec.decodeVideo(new cn.piq.fcarcade.cabinet.CabinetMediaCodec.Encoded(h.width(),h.height(),h.aspect(),h.rotation(),complete.bytes()));
                if(h.rotation()!=0)throw new IllegalArgumentException("SFC home rotation must be zero");
                byte[] rgba=new byte[abgr.length*4];for(int i=0;i<abgr.length;i++){int p=abgr[i];rgba[i*4]=(byte)p;rgba[i*4+1]=(byte)(p>>>8);rgba[i*4+2]=(byte)(p>>>16);rgba[i*4+3]=(byte)(p>>>24);}
                if(!mediaFreshness.decodedVideo())throw mediaTimeout();checkMediaFreshness();
                pending.set(new Picture(h.width(),h.height(),h.width()*4,h.aspect(),rgba));receivedFrameMeter.run();started=true;startup.enter(SfcStartupProgress.Stage.RUNNING);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception|LinkageError failure){if(running)error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
        finally{running=false;mediaInput.clear();if(audio!=null)audio.close();}
    }
    private IllegalStateException mediaTimeout(){return new IllegalStateException(session.playerHosted()?"SFC 主持画面超时，已退出控制；未重开游戏":"SFC 服务端画面超时，已退出控制");}
    private void checkMediaFreshness(){
        var phase=mediaFreshness.status().phase();
        if(phase==SfcMediaFreshness.Phase.TIMED_OUT)throw mediaTimeout();
        if(phase!=mediaPhase){
            if(phase==SfcMediaFreshness.Phase.STALLED)mediaNotice.set("SFC 画面已停滞，已暂停输入；正在等待画面恢复");
            else if(phase==SfcMediaFreshness.Phase.LIVE&&mediaPhase==SfcMediaFreshness.Phase.STALLED)mediaNotice.set("SFC 画面已恢复，请先松开按键再操作");
            mediaPhase=phase;
        }
    }
    boolean mediaInputPaused(){return session.receivesMedia()&&mediaFreshness.status().paused();}
    long mediaInterruption(){return session.receivesMedia()?mediaFreshness.status().interruption():0;}
    String pollMediaNotice(){return mediaNotice.getAndSet(null);}
    String pollAudioNotice(){return audioNotice.getAndSet(null);}
    private void observeAudioFailure(Audio audio){
        if(!audioWarningShown&&audio.failureMessage()!=null){
            // SfcAudioPlayer already logs its original device exception once.
            // Keep this user notice non-blocking and do not expose exception details.
            audioWarningShown=true;
            audioNotice.compareAndSet(null,"SFC 音频设备不可用，游戏仍在运行；请检查系统输出设备，正常关机后重开可重试声音");
        }
    }
    void resetMedia(){if(session.receivesMedia()){mediaFreshness.reset();mediaInput.clear();pending.set(null);resetMedia=true;}}
    boolean offer(SfcHomeNetwork.Frames frames) {if(!running)return false;if(faultRequested&&repair==null)return true;boolean accepted=input.offer(new FrameBatch(frames.firstFrame(),frames.p1Masks(),frames.p2Masks()));if(accepted)started=true;else if(!session.executionHost()&&host.consistencyChecks()&&repair==null){requestRepairFault(observedFrame);return true;}return accepted;}
    boolean synchronizationPaused(){return faultRequested||repairPaused||repair!=null;}
    /** Same bounded worker queue, but no controller capability or gameplay input is created. */
    boolean offerObserved(SfcRepairNetwork.Replay frames){return running&&input.offer(new FrameBatch(frames.key().frame(),frames.p1(),frames.p2()));}
    private void requestRepairFault(int frame){if(session.executionHost()||faultRequested)return;faultBegan=System.nanoTime();faultRequested=true;repairPaused=true;input.clear();host.execute(()->{if(running&&host.isCurrent(this))host.synchronizationFault(this,frame);});}
    SfcCheckpoints.Entry checkpoint(int frame){return checkpoints.get(frame);}
    void beginRepair(SfcRepairNetwork.Key key){if(!running||!session.checksState()||session.executionHost()||!matches(key.session(),key.epoch())||!session.controllerLease().equals(key.lease()))return;repairPaused=true;repairResume=-1;repair=key;repairRestore=null;input.clear();}
    private boolean repairs(SfcRepairNetwork.Key key){var current=repair;return running&&current!=null&&current.token().equals(key.token())&&current.lease().equals(key.lease())&&matches(key.session(),key.epoch());}
    void restoreRepair(SfcRepairNetwork.Key key,byte[] bytes,String sha){if(repairs(key))repairRestore=new RepairRestore(key,bytes,sha);}
    boolean replayRepair(SfcRepairNetwork.Replay p){return repairs(p.key())&&input.offer(new FrameBatch(p.key().frame(),p.p1(),p.p2()));}
    void resumeRepair(SfcRepairNetwork.Key key){if(repairs(key))repairResume=key.frame();}
    private boolean finishRepairFrame(int frame,int inFlightFrames){var base=repair;if(base==null||repairPaused)return false;
        int queued=inFlightFrames;for(var batch:input){queued+=batch.p1Masks().length;if(queued>SfcRepairProgress.MAX_QUEUED_FRAMES)return false;}
        if(!SfcRepairProgress.ready(frame,repairResume,queued))return false;
        var done=new SfcRepairNetwork.Key(base.session(),base.epoch(),base.lease(),base.token(),frame);repair=null;repairResume=-1;faultRequested=false;
        host.execute(()->{if(running&&host.isCurrent(this))host.repairedFrames(this,done,true);});return true;}
    boolean started(){return started;}
    String error() { return error; }
    /** Required player-hosted media must never fail silently while the authoritative core advances. */
    void playerMediaFailed(){
        if(!running||!session.playerHosted()||!session.executionHost())return;
        error="SFC 主持音画发布失败，已安全停止；本机恢复备份不等同于服务器存档";
        running=false;thread.interrupt();
    }
    ResourceLocation textureId() { return textureId; }
    double aspect() { return aspect; }
    cn.piq.retro.api.RetroFrame watchFrame(){
        Picture frame=watchPicture;
        return frame==null?null:SfcWatchFrames.copy(frame.width,frame.height,frame.stride,(float)frame.aspect,frame.rgba,new short[0],0);
    }
    void gain(float value) { gain=Math.max(0,Math.min(1,value)); }
    void capture(SfcJoinNetwork.Capture request){if(running&&session.checksState()&&session.executionHost()&&matches(request.session(),request.epoch()))capture=request;}
    void restore(SfcJoinNetwork.Capture request,byte[] bytes,String sha){if(running&&session.checksState()&&!session.executionHost()&&matches(request.session(),request.epoch()))restore=new Restore(request,bytes,sha);}
    void cancelJoin(java.util.UUID token){if(capture!=null&&capture.token().equals(token))capture=null;if(restore!=null&&restore.request.token().equals(token))restore=null;}
    private void run() {
        int nextFrame=0;
        var pacing=new SfcPlaybackPacing();
        startup.enter(SfcStartupProgress.Stage.CORE_CREATE);
        try (SfcExecutionCore core=new SfcExecutionCore()) {
            if(!running)return;
            startup.enter(SfcStartupProgress.Stage.ROM_LOAD);
            core.loadRom(SfcRomImage.fromBytes(rom));
            if(!running)return;
            startup.enter(SfcStartupProgress.Stage.INITIAL_STATE);
            double fps=core.initialize();
            byte[] initialState=core.saveState();
            String initialHash=SfcClientFiles.hash(initialState);
            if(session.executionHost()&&session.checksState()&&host.consistencyChecks())checkpoints.put(0,initialState);
            if(!running)return;
            startup.enter(SfcStartupProgress.Stage.AUDIO);
            try(Audio audio=host.openAudio()){
            startup.enter(SfcStartupProgress.Stage.READY);
            host.execute(()-> {
                if (running && host.isCurrent(this)) host.ready(this,new SfcHomeNetwork.Ready(
                        session.sessionId(),session.epoch(),session.romSha(),SfcHomeNetwork.CORE_BUILD,fps,initialHash));
            });
            long nanos=Math.max(1,Math.round(1_000_000_000.0/fps)), deadline=System.nanoTime();
            byte[] pixels=new byte[0]; short[] pcm=new short[4096];boolean catchupAudioFailed=false;
            try {
                while(running) {
                    RepairRestore correction=repairRestore;
                    if(correction!=null){repairRestore=null;var key=correction.key;
                        try{if(!repairs(key))continue;core.loadState(correction.bytes);String digest=SfcExecutionCore.digest(core.saveState());if(!digest.equals(correction.sha))throw new IllegalStateException("Repair digest differs");
                            nextFrame=key.frame();observedFrame=nextFrame;repairPaused=false;pacing.reset();deadline=System.nanoTime();host.execute(()->{if(running&&host.isCurrent(this)&&repairs(key))host.repairedState(this,key,digest,true);});
                        }catch(RuntimeException failure){host.execute(()->{if(running&&host.isCurrent(this)&&repairs(key))host.repairedState(this,key,"",false);});}
                    }
                    if(finishRepairFrame(nextFrame,0))deadline=System.nanoTime();
                    if(repairPaused){if(faultRequested&&repair==null&&System.nanoTime()-faultBegan>30_000_000_000L)throw new IllegalStateException("本端同步修复请求超时，已退出控制；主机继续");LockSupport.parkNanos(1_000_000L);continue;}
                    Restore replacement=restore;
                    if(replacement!=null){
                        restore=null;var request=replacement.request;
                        try{core.loadState(replacement.bytes);String digest=SfcClientFiles.hash(core.saveState());
                            if(!digest.equals(replacement.sha))throw new IllegalStateException("Imported state differs");
                            nextFrame=request.frame();observedFrame=nextFrame;pacing.reset();deadline=System.nanoTime();
                            host.execute(()->{if(running&&host.isCurrent(this))host.applied(this,request,digest,true);});
                        }catch(RuntimeException failed){host.execute(()->{if(running&&host.isCurrent(this))host.applied(this,request,"",false);});}
                    }
                    var request=capture;
                    if(request!=null&&nextFrame>=request.frame()){
                        capture=null;
                        try{if(nextFrame!=request.frame())throw new IllegalStateException("Snapshot boundary was missed");
                            byte[] bytes=core.saveState();if(bytes.length<1||bytes.length>cn.piq.sfchome.server.SfcJoinGate.MAX_STATE)throw new IllegalStateException("Snapshot too large");
                            String digest=SfcClientFiles.hash(bytes);
                            host.execute(()->{if(running&&host.isCurrent(this))host.captured(this,request,bytes,digest);});
                        }catch(RuntimeException failed){host.execute(()->{if(running&&host.isCurrent(this))host.applied(this,request,"",false);});}
                    }
                    FrameBatch frames=input.poll(250,TimeUnit.MILLISECONDS);
                    if(frames==null) continue;
                    if(repairPaused)continue;
                    if(finishRepairFrame(nextFrame,frames.p1Masks().length))deadline=System.nanoTime();
                    int[] p1=frames.p1Masks(),p2=frames.p2Masks();
                    if(frames.firstFrame()!=nextFrame || p1.length!=p2.length){if(!session.executionHost()&&host.consistencyChecks()&&repair==null){requestRepairFault(nextFrame);continue;}throw new IllegalStateException("SFC 输入帧缺失，已停止以免双人不同步");}
                    for(int i=0;i<p1.length && running;i++) {
                        if(repairPaused)break;
                        long now=System.nanoTime();
                        int outstanding=p1.length-i;for(var queued:input)outstanding+=queued.p1Masks().length;
                        var pace=pacing.frame(outstanding,now,repair!=null);
                        if(pace.expired()){
                            if(!session.executionHost()&&host.consistencyChecks()&&repair==null){requestRepairFault(nextFrame);break;}
                            throw new IllegalStateException("SFC 连续追帧超过 10 秒仍未赶上，已安全停止；没有重置游戏进度");
                        }
                        if(pace.entered()){
                            try{audio.discardQueued();}catch(RuntimeException|LinkageError failure){
                                catchupAudioFailed=true;
                                LoggerFactory.getLogger("PIQ SFC Home").warn("SFC catch-up audio reset failed; local audio muted, simulation continues",failure);
                            }
                            pending.set(null);
                            LoggerFactory.getLogger("PIQ SFC Home").info("SFC session {} catching up from frame {}, queued frames {}",session.sessionId(),nextFrame,outstanding);
                        }
                        if(pace.recovered()){
                            deadline=now;
                            LoggerFactory.getLogger("PIQ SFC Home").info("SFC session {} caught up at frame {}, queued frames {}",session.sessionId(),nextFrame,outstanding);
                        }
                        if(pace.yieldNanos()>0)LockSupport.parkNanos(pace.yieldNanos());
                        if(now-deadline>4*nanos) deadline=now;
                        long delay=deadline-now; if(delay>0&&repair==null&&!pace.catchingUp()) LockSupport.parkNanos(delay);
                        if(!running) break;
                        var result=core.runFrame(new SfcControllerState(p1[i]),new SfcControllerState(p2[i]));
                        if(startup.stage()!=SfcStartupProgress.Stage.RUNNING){startup.enter(SfcStartupProgress.Stage.RUNNING);LoggerFactory.getLogger("PIQ SFC Home").info("SFC session {} first frame; startup stage nanoseconds {}",session.sessionId(),startup.timings());}
                        var mode=result.videoMode();
                        if(Math.abs(mode.targetFramesPerSecond()-fps)>0.01) throw new IllegalStateException("游戏运行时切换时序，首版暂不支持");
                        if(pcm.length<result.requiredPcmShorts()) pcm=new short[result.requiredPcmShorts()];
                        int pcmLength=core.copyAudioPcm16(pcm);
                        if(repair==null&&!pace.catchingUp()&&!catchupAudioFailed)audio.submit(pcm,pcmLength,gain);
                        observeAudioFailure(audio);
                        if(pixels.length!=mode.requiredRgbaBytes()) pixels=new byte[mode.requiredRgbaBytes()];
                        core.copyRgbaFrame(pixels);
                        if(!pace.catchingUp()){
                            Picture picture=new Picture(mode.width(),mode.height(),mode.rowStrideBytes(),mode.width()*mode.pixelAspectRatio()/mode.height(),pixels.clone());
                            pending.set(picture);watchPicture=picture;
                        }
                        nextFrame++;observedFrame=nextFrame; deadline+=nanos;
                        if(session.checksState()&&host.consistencyChecks()&&repair==null&&nextFrame%SfcRepairLedger.INTERVAL==0){var checkpoint=checkpoints.put(nextFrame,core.saveState());int at=nextFrame;host.execute(()->{if(running&&host.isCurrent(this))host.digest(this,at,checkpoint.sha());});}
                        host.observedFrame(nextFrame,mode.width(),mode.height(),pixels,pcm,pcmLength);
                        if(session.executionHost()&&!mediaFailed&&!pace.catchingUp()){
                            try{host.mediaFrame(this,mode.width(),mode.height(),mode.rowStrideBytes(),(float)(mode.width()*mode.pixelAspectRatio()/mode.height()),pixels,pcm,pcmLength);}
                            catch(RuntimeException|LinkageError failure){
                                if(session.playerHosted())throw new IllegalStateException("SFC 主持音画发布失败，已安全停止；本机恢复备份不等同于服务器存档",failure);
                                mediaFailed=true;LoggerFactory.getLogger("PIQ SFC Home").warn("SFC spectator publication disabled; controller playback continues",failure);
                            }
                        }
                        if(session.executionHost() && nextFrame%1800==0) backup(core,nextFrame);
                    }
                }
            } finally { Thread.interrupted(); if(session.executionHost() && nextFrame>0) backup(core,nextFrame); }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Throwable failure) {
            if(running) { error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                LoggerFactory.getLogger("PIQ SFC Home").error("SFC home playback stopped",failure); }
        } finally { running=false;lease.close(); }
    }
    private void backup(SfcExecutionCore core,int frame) {
        String notice;
        try { host.backup(this,core.nativeCore(),frame);notice=backupStatus.success(frame); }
        catch(Exception failure) {
            notice=backupStatus.failure();
            if(notice!=null)LoggerFactory.getLogger("PIQ SFC Home").warn("Local SFC recovery backup failed; not a server save",failure);
        }
        if(notice!=null){
            try{host.backupNotice(this,notice);}
            catch(RuntimeException warningFailure){LoggerFactory.getLogger("PIQ SFC Home").warn("Could not display local backup notice",warningFailure);}
        }
    }
    void upload() {
        Picture frame=pending.getAndSet(null); if(frame==null) return;
        if(texture==null || texture.getPixels()==null || texture.getPixels().getWidth()!=frame.width || texture.getPixels().getHeight()!=frame.height) {
            release(); texture=new DynamicTexture(frame.width,frame.height,false); texture.setFilter(false,false);
            textureId=ResourceLocation.fromNamespaceAndPath("piq_sfc_home","frame/"+session.sessionId()+"_"+session.epoch());
            Minecraft.getInstance().getTextureManager().register(textureId,texture);
        }
        NativeImage image=texture.getPixels();
        for(int y=0;y<frame.height;y++) for(int x=0;x<frame.width;x++) {
            int p=y*frame.stride+x*4;
            image.setPixelRGBA(x,y,(frame.rgba[p]&255)|((frame.rgba[p+1]&255)<<8)|((frame.rgba[p+2]&255)<<16)|((frame.rgba[p+3]&255)<<24));
        }
        texture.upload(); aspect=frame.aspect;
    }
    private void release() {
        if(textureId!=null) Minecraft.getInstance().getTextureManager().release(textureId);
        else if(texture!=null) texture.close();
        texture=null; textureId=null;
    }
    @Override public void close() { running=false;if(netplayStart!=null)netplayStart.close();capture=null;restore=null;repair=null;repairRestore=null;checkpoints.clear(); thread.interrupt(); pending.set(null);watchPicture=null; input.clear();mediaInput.clear(); release();try{host.mediaClosed(this);}catch(RuntimeException|LinkageError ignored){} }
    private record FrameBatch(int firstFrame,int[] p1Masks,int[] p2Masks){}
    private record RepairRestore(SfcRepairNetwork.Key key,byte[] bytes,String sha){}
    private record Restore(SfcJoinNetwork.Capture request,byte[] bytes,String sha){}
    private record Picture(int width,int height,int stride,double aspect,byte[] rgba) {}
}
