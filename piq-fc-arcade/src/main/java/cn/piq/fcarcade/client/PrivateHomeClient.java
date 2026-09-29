package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.privateplay.*;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.fcarcade.client.cabinet.WatchAudio;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.world.*;
import cn.piq.retro.client.*;
import cn.piq.retro.input.InputOwnership;
import cn.piq.retro.libretro.LibretroRuntimes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** An entirely client-owned runtime. Physical receipts remain normal Minecraft inventory data.
 * No public emulator session, cartridge mutation, transfer, snapshot or watch publisher is used. */
public final class PrivateHomeClient {
    public interface Provider extends ControllerCapture.Provider {
        String label();
        String storageKey();
        /** Local selector validation belongs to the provider, not a growing list of system IDs. */
        default boolean acceptsFile(String name){return storageKey().equals("fc")?name.endsWith(".nes"):name.endsWith(".sfc")||name.endsWith(".smc");}
        default String fileHint(){return storageKey().equals("fc")?".nes":".sfc / .smc";}
        PrivateEngine create(Path rom, Path saveRoot);
        /** Optional explicit trial; old addons preserve their default process behavior. */
        default boolean supportsJniTrial(){return false;}
        default PrivateEngine create(Path rom,Path saveRoot,LibretroRuntimes.Backend backend){
            if(backend!=LibretroRuntimes.Backend.PROCESS)throw new IllegalArgumentException("此附属尚未接入 JNI 私人试验");
            return create(rom,saveRoot);
        }
        default boolean publicBusy(){return false;}
        default UUID identity(ItemStack stack){return lease(stack);}
    }
    record Target(Provider provider, Object connection, Object level, UUID player, UUID lease,
                  BlockEntity console, UUID hardware, HomeTvBlockEntity tv, UUID television, UUID link, boolean tvPower) {}
    private static final Map<ResourceLocation,Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Set<Runnable> BEFORE_START = new LinkedHashSet<>();
    private static Run current;
    private static boolean installed, opening, sampling, closing;
    private static java.util.concurrent.CompletableFuture<PrivateEngine.SaveResult> pendingSave;
    private static String notice = "请先接好电视，借出并手持实体手柄；公开主机须处于关机状态。";
    private static final class Run {
        final Target target; final Object owner; final PrivateEngine engine; final boolean trial;
        DynamicTexture texture; ResourceLocation textureId; WatchAudio audio;
        double aspect = 4.0/3; boolean paused = true; int lastMask = -1, hand = -1;
        Run(Target target, Object owner, Path rom, Path saveRoot,LibretroRuntimes.Backend backend) {
            this.target=target;this.owner=owner;trial=backend==LibretroRuntimes.Backend.JNI_TRIAL;
            engine=target.provider.create(rom,saveRoot,backend);engine.paused(true);
        }
    }
    private PrivateHomeClient() {}
    public static void register(ResourceLocation id, Provider provider) {
        if(!provider.storageKey().matches("[a-z0-9_-]{1,24}") || PROVIDERS.size()>=4 || PROVIDERS.putIfAbsent(id,provider)!=null)
            throw new IllegalArgumentException("Private home provider");
    }
    static void install() {
        if(installed)return; installed=true;
        register(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","nes"),new Provider(){
            public String label(){return "FC / 小霸王";}
            public String storageKey(){return "fc";}
            public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.NES;}
            public int[][] keys(){return ArcadeKeyMappings.legacyKeys();}
            public UUID lease(ItemStack stack){var r=HomeControllerData.receipt(stack);return r==null?null:r.lease();}
            public UUID identity(ItemStack stack){return HomeControllerData.leaseId(stack);}
            public boolean publicBusy(){return ClientArcadeEvents.hasHomeParticipant()||ClientRomTransfers.hasPendingParticipant();}
            public boolean matches(Player player,ItemStack stack,BlockEntity endpoint){
                var r=HomeControllerData.receipt(stack);
                return r!=null&&r.session()==0&&r.dimension().equals(player.level().dimension().location().toString())
                        &&endpoint instanceof HomeConsoleBlockEntity c&&!c.visualPowered()&&r.console().equals(c.hardwareId())
                        &&ControllerCapturePolicy.receipt(player.getUUID(),r.lease(),r.port(),c.controllerVisualPlayer(r.port()),
                            c.controllerVisualLease(r.port()),c.controllerDocked(r.port()),player.distanceToSqr(c.getBlockPos().getCenter()));
            }
            public PrivateEngine create(Path rom,Path root){return new FcPrivateEngine(rom,root);}
            public boolean supportsJniTrial(){return true;}
            public PrivateEngine create(Path rom,Path root,LibretroRuntimes.Backend backend){return new FcPrivateEngine(rom,root,backend);}
        });
        ControllerCapture.registerRuntime(PrivateHomeClient::refreshKeyboard);
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e)->e.getDispatcher().register(
                Commands.literal("gameconsole-private").executes(c->{opening=true;return 1;})
                    .then(Commands.literal("stop").executes(c->{stop("已请求结束私人游戏");return 1;}))));
        NeoForge.EVENT_BUS.addListener(PrivateHomeClient::tick);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e)->update());
        NeoForge.EVENT_BUS.addListener(PrivateHomeClient::render);
        NeoForge.EVENT_BUS.addListener(PrivateHomeClient::interaction);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{opening=false;stop("已离开服务器");});
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e)->shutdown());
        NetworkDiagnosticsClient.registerDevices("private-home",()->current==null?List.of():List.of(
                new NetworkDiagnosticsView.Device(current.target.provider.label(),NetworkDiagnosticsView.Mode.PRIVATE_LOCAL,
                        NetworkDiagnosticsView.Role.CONTROLLING,0)));
    }
    /** Client setup hook: passive observers must yield before an explicit private-play request. */
    public static void registerBeforeStart(Runnable callback) {
        Objects.requireNonNull(callback);
        if(BEFORE_START.size()>=4&&!BEFORE_START.contains(callback))throw new IllegalStateException("Private start hook limit");
        BEFORE_START.add(callback);
    }
    public static boolean isActiveOrClosing() {
        return current!=null||closing||opening||Minecraft.getInstance().screen instanceof PrivateHomeScreen;
    }
    private static boolean yieldObservers() {
        try{for(var callback:List.copyOf(BEFORE_START))callback.run();return true;}
        catch(RuntimeException|LinkageError failure){notice="旁观运行器尚未释放，请稍后重试私人模式。";return false;}
    }
    public static void open() { if(yieldObservers())Minecraft.getInstance().setScreen(new PrivateHomeScreen()); }
    static boolean active(){return current!=null;}
    static FcPerformanceView.Entry performance(boolean enabled) {
        var r=current;
        if(r==null||!(r.engine instanceof FcPrivateEngine engine))return null;
        engine.performanceEnabled(enabled);
        if(!enabled)return null;
        var p=engine.performance();
        var device=new NetworkDiagnosticsView.Device("FC 私人 @ "+r.target.console.getBlockPos().toShortString(),
                NetworkDiagnosticsView.Mode.PRIVATE_LOCAL,NetworkDiagnosticsView.Role.CONTROLLING,0);
        return new FcPerformanceView.Entry("private:"+r.target.lease,device,p.state(),p.sample(),p.frames(),-1,p.queued(),-1);
    }
    static boolean busy(){return closing;}
    static String status(){return notice;}
    static String targetLabel(Target selected){var t=current==null?selected:current.target;return t==null?"未手持已借出的空闲家用机手柄":t.provider.label()+" @ "+t.console.getBlockPos().toShortString();}
    static Target find() {
        var mc=Minecraft.getInstance(); if(!connected())return null;
        for(var held:List.of(mc.player.getMainHandItem(),mc.player.getOffhandItem()))for(var p:PROVIDERS.values()){
            UUID lease=p.lease(held);if(lease==null||!ControllerCapture.unique(mc.player,held,lease,p::identity))continue;
            var console=p.locate(mc.player,held);if(console==null)continue;
            BlockPos pos=console instanceof HomeConsoleBlockEntity fc?fc.tvPos():console instanceof ExternalHomeConsoleBlockEntity ex?ex.televisionPos():null;
            if(pos==null||!mc.level.hasChunkAt(pos)||!(mc.level.getBlockEntity(pos) instanceof HomeTvBlockEntity tv))continue;
            var t=new Target(p,mc.getConnection(),mc.level,mc.player.getUUID(),lease,console,hardware(console),tv,tv.hardwareId(),link(console),tv.powered());
            if(valid(t))return t;
        }
        return null;
    }
    static String start(Target target,String filename) {
        return start(target,filename,defaultBackend(target));
    }
    static LibretroRuntimes.Backend defaultBackend(Target target){
        return LibretroRuntimes.defaultBackend(target!=null&&target.provider.supportsJniTrial());
    }
    static String start(Target target,String filename,LibretroRuntimes.Backend backend) {
        Objects.requireNonNull(backend);
        if(current!=null||closing)return "请先结束上一局并等待本地保存完成。";
        if(!yieldObservers())return notice;
        if(target==null||!valid(target)||held(target)==null)return "手柄、连接或主机状态已变化，请重新借取空闲主机的手柄。";
        if(backend==LibretroRuntimes.Backend.JNI_TRIAL){
            if(!target.provider.supportsJniTrial())return "此附属尚未接入 JNI 私人试验，请选择独立进程。";
            String unavailable=LibretroRuntimes.jniUnavailableReason();if(!unavailable.isEmpty())return unavailable;
            if(LibretroRuntimes.isJniBusy())return "已有 JNI 会话运行或尚未安全退出；请等其结束或明确切回独立进程，不会自动更换存档。";
        }
        final Path rom,root;
        try{
            rom=Path.of(filename.strip());
            if(!rom.isAbsolute())return "请填写本机 ROM 的完整路径，例如 D:\\Games\\demo.nes。";
            String name=rom.getFileName().toString().toLowerCase(Locale.ROOT);
            if(!target.provider.acceptsFile(name))
                return "文件扩展名与机型不符："+target.provider.label()+" 使用 "+target.provider.fileHint()+"。";
            // Account + server address isolate private progress; no ROM path/name is written to a receipt.
            var mc=Minecraft.getInstance();var server=mc.getCurrentServer();
            String identity=server==null?"local-integrated":server.ip;
            String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            String directory=backend==LibretroRuntimes.Backend.JNI_TRIAL?"piq-private-home-jni-v1":"piq-private-home";
            root=cn.piq.retro.storage.ConsoleStorage.root(mc.gameDirectory.toPath()).resolve(directory).resolve(target.player.toString()).resolve(key);
        }catch(Exception invalid){return "路径无效，请粘贴本机 ROM 文件的完整路径。";}
        // Reserve input before opening a core. Other clients keep their own independent inventory/game state.
        Object reservation=new Object();
        if(!CabinetClientOwner.acquire(reservation))return "请先退出正在操作的公开游戏或街机。";
        try {
            Run run=new Run(target,reservation,rom,root,backend);
            current=run;notice=(run.trial?"JNI 私人试验（独立试验档）":"私人模式")+"正在本机启动；不上传游戏数据。";
            refreshKeyboard();return null;
        }catch(RuntimeException|LinkageError failure){return "本机启动失败："+failure.getMessage();}
        finally {if(current==null||current.owner!=reservation)CabinetClientOwner.release(reservation);}
    }
    private static boolean connected(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.level!=null&&mc.player.isAlive()
            &&!mc.player.isSpectator()&&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected();}
    private static UUID hardware(BlockEntity c){return c instanceof HomeConsoleBlockEntity fc?fc.hardwareId():c instanceof ExternalHomeConsoleBlockEntity ex?ex.hardwareId():null;}
    private static UUID link(BlockEntity c){return c instanceof HomeConsoleBlockEntity fc?fc.linkId():c instanceof ExternalHomeConsoleBlockEntity ex?ex.linkId():null;}
    private static ItemStack owned(Target t){
        var p=Minecraft.getInstance().player;if(p==null)return null;
        for(int i=0;i<p.getInventory().getContainerSize();i++){
            var stack=p.getInventory().getItem(i);
            if(t.lease.equals(t.provider.lease(stack))&&ControllerCapture.unique(p,stack,t.lease,t.provider::identity))return stack;
        }
        return null;
    }
    private static ItemStack held(Target t){var p=Minecraft.getInstance().player;if(p==null)return null;
        for(var stack:List.of(p.getMainHandItem(),p.getOffhandItem()))if(t.lease.equals(t.provider.lease(stack))
                &&ControllerCapture.unique(p,stack,t.lease,t.provider::identity))return stack;return null;}
    private static boolean valid(Target t) {
        var mc=Minecraft.getInstance();
        if(t==null||!connected()||mc.getConnection()!=t.connection||mc.level!=t.level||!mc.player.getUUID().equals(t.player)
                ||t.console.isRemoved()||t.tv.isRemoved()||!mc.level.hasChunkAt(t.console.getBlockPos())||!mc.level.hasChunkAt(t.tv.getBlockPos())
                ||mc.level.getBlockEntity(t.console.getBlockPos())!=t.console||mc.level.getBlockEntity(t.tv.getBlockPos())!=t.tv
                ||!Objects.equals(t.hardware,hardware(t.console))||!t.television.equals(t.tv.hardwareId())||t.link==null
                ||!t.link.equals(link(t.console))||!t.link.equals(t.tv.linkId())||t.tv.signalPresent()
                ||t.tv.powered()!=t.tvPower||PROVIDERS.values().stream().anyMatch(Provider::publicBusy))return false;
        var stack=owned(t);
        return stack!=null&&t.provider.matches(mc.player,stack,t.console)&&HomeHardware.connected(mc.level,t.console,t.tv);
    }
    private static void tick(ClientTickEvent.Pre event){
        if(opening){opening=false;if(connected())open();}
        update();
    }
    private static void refreshKeyboard(){var r=current;if(r==null)return;
        if(!valid(r.target)||!InputOwnership.owns(r.owner)){stop("手柄或设备已失效，私人游戏已停止");return;}
        var held=held(r.target);int hand=held==null?-1:Minecraft.getInstance().player.getMainHandItem()==held?0:1;
        if(hand!=r.hand){r.hand=hand;KeyboardInput.pause(r.owner);releaseKeys(r);}
        KeyboardInput.attach(r.owner,r.target.provider.profile(),r.target.provider::keys,()->current==r&&valid(r.target)&&held(r.target)!=null,
                ()->current==r&&r.engine.isReady()&&InputOwnership.owns(r.owner),()->releaseKeys(r),PrivateHomeClient::sample);
    }
    private static void releaseKeys(Run r){r.engine.clearInput();r.lastMask=-1;GamepadInput.pause(r.owner);}
    private static void sample(){
        if(sampling)return;sampling=true;
        try{var r=current;if(r==null)return;var mc=Minecraft.getInstance();
            boolean focused=valid(r.target)&&held(r.target)!=null&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()&&r.engine.isReady();
            if(r.paused!=!focused){r.paused=!focused;r.engine.paused(r.paused);releaseKeys(r);if(r.audio!=null){r.audio.close();r.audio=null;}}
            if(!focused){KeyboardInput.pause(r.owner);return;}
            var k=KeyboardInput.poll(r.owner,0,true);
            int mask=GamepadInput.mix(r.owner,GamepadInput.ProfileKind.valueOf(r.target.provider.profile().name()),k.mask(),k.armed());
            if(!k.armed())mask=0;
            if(r.target.provider.profile()==KeyboardConfig.Profile.NES)mask=canonicalNes(mask);
            if(mask!=r.lastMask){r.engine.offerInput(mask,0);r.lastMask=mask;}
        }finally{sampling=false;}
    }
    static int canonicalNes(int nes){return (nes&0xfc)|((nes&1)<<8)|((nes&2)>>>1);}
    private static void update(){
        var r=current;if(r==null)return;
        try{
            if(!valid(r.target)){stop("已归还手柄、切换世界或设备状态改变");return;}
            if(r.engine.error()!=null){stop("私人游戏出错："+r.engine.error());return;}
            refreshKeyboard();if(current!=r)return;sample();
            CabinetFrame last=null,frame;
            for(int i=0;i<8&&(frame=r.engine.pollFrame())!=null;i++){
                last=frame;if(!r.paused&&frame.pcm48k().length>0){if(r.audio==null)r.audio=new WatchAudio();
                    var mc=Minecraft.getInstance();r.audio.gain((r.target.tv.muted()?0:r.target.tv.volume()/100f)*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS));r.audio.offer(frame.pcm48k());}
            }
            if(last!=null)upload(r,last);
            if(r.engine.isReady())notice=(r.trial?"JNI 私人试验 · 独立试验档 · ":"")+(r.paused?"私人模式已暂停；关闭菜单并手持手柄后继续。":"私人模式运行中：游戏数据仅在本机；另一只手柄不会加入本局。");
        }catch(RuntimeException|LinkageError failure){stop("私人显示或输入异常："+failure.getClass().getSimpleName());}
    }
    private static void upload(Run r,CabinetFrame frame){
        if(r.texture==null||r.texture.getPixels().getWidth()!=frame.width()||r.texture.getPixels().getHeight()!=frame.height()){
            if(r.textureId!=null)Minecraft.getInstance().getTextureManager().release(r.textureId);
            r.texture=new DynamicTexture(frame.width(),frame.height(),false);r.texture.setFilter(false,false);
            r.textureId=Minecraft.getInstance().getTextureManager().register("private_home",r.texture);
        }
        for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++)r.texture.getPixels().setPixelRGBA(x,y,frame.abgr()[y*frame.width()+x]);
        r.texture.upload();r.aspect=frame.displayAspect();
    }
    public static void stop(String reason){
        Run r=current;if(r==null)return;current=null;closing=true;
        notice=reason+(r.engine.error()!=null?"；等待运行器安全退出，尚未确认保存。":"；正在保存到本机。");
        KeyboardInput.release(r.owner);GamepadInput.release(r.owner);r.engine.clearInput();
        CabinetClientOwner.release(r.owner);
        if(r.audio!=null)r.audio.close();if(r.textureId!=null)Minecraft.getInstance().getTextureManager().release(r.textureId);
        pendingSave=r.engine.stopAndSave();
        pendingSave.whenComplete((saved,error)->Minecraft.getInstance().execute(()->{
            closing=false;notice=reason+"；"+(error!=null?"本地保存失败，未确认保存成功。":saved.message());
            var mc=Minecraft.getInstance();if(mc.player!=null&&mc.getConnection()==r.target.connection)mc.player.displayClientMessage(Component.literal(notice),false);
        }));
    }
    private static void interaction(InputEvent.InteractionKeyMappingTriggered event){
        var r=current;var mc=Minecraft.getInstance();
        if(r==null||event.isCanceled()||!event.isUseItem()||mc.screen!=null||!valid(r.target))return;
        boolean holding=held(r.target)!=null;
        if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit){
            var pos=hit.getBlockPos();
            boolean own=pos.equals(r.target.console.getBlockPos())||pos.equals(r.target.tv.getBlockPos())
                    ||r.target.tv.getBlockPos().equals(HomeTvStructure.resolveAnchor(mc.level,pos))
                    ||r.target.console.getBlockPos().equals(SuborStructure.resolveAnchor(mc.level,pos));
            if(own){
                var eye=mc.player.getEyePosition();var control=HomeApplianceService.controlAt(mc.level,pos,eye,
                        eye.add(mc.player.getLookAngle().scale(Math.min(6,mc.player.blockInteractionRange()))));
                if(control.port()>=0&&holding){stop("归还实体手柄");return;}
                if(control==HomeApplianceControl.POWER||control==HomeApplianceControl.RESET){
                    event.setCanceled(true);event.setSwingHand(false);stop("已结束私人游戏，未改变公共设备电源");return;
                }
            }
        }
        if(holding){event.setCanceled(true);event.setSwingHand(false);open();}
    }
    private static void shutdown(){
        stop("正在退出客户端");var pending=pendingSave;if(pending==null)return;
        try{pending.get(8,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception error){cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Private save did not confirm before shutdown; previous saves retained",error);}
    }
    static boolean ownsDisplay(HomeTvBlockEntity tv){return current!=null&&current.target.tv==tv&&valid(current.target);}
    static boolean ownsConsole(BlockEntity console){return current!=null&&current.target.console==console&&valid(current.target);}
    private static void render(RenderLevelStageEvent event){
        var r=current;
        if(r==null||r.textureId==null||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||!valid(r.target))return;
        var mc=Minecraft.getInstance();var tv=r.target.tv;var pos=tv.getBlockPos();var layout=ArcadeStructure.resolve(mc.level,pos);
        if(!layout.anchor().equals(pos)||!(tv.getBlockState().getBlock() instanceof FcArcadeBlock block))return;
        var stack=event.getPoseStack();stack.pushPose();
        try{
            if(!HomeScreenRenderPose.apply(event,mc.level,pos))return;
            if(block.displayStyle()==cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV){
                var offset=cn.piq.fcarcade.layout.ScreenSurfaceGeometry.translation(block.displayStyle(),
                    cn.piq.fcarcade.layout.RocketArcadeGeometry.quarterTurns(layout.facing().getStepX(),layout.facing().getStepZ()),HomeTvStructure.centered(tv.getBlockState()));
                stack.translate(offset.x(),offset.y(),offset.z());
            }
            var buffers=mc.renderBuffers().bufferSource();var type=ScreenRenderMaterial.of(r.textureId);
            var consumer=new CrtScanlineVertexConsumer(buffers.getBuffer(type),event.getProjectionMatrix(),mc.getWindow().getWidth(),mc.getWindow().getHeight(),r.aspect,tv.scanlinesEnabled());
            ArcadeBlockScreenRenderer.drawFace(consumer,stack.last(),layout.facing(),layout.width(),layout.height(),block.displayStyle(),1);
            buffers.endBatch(type);
        }finally{stack.popPose();}
    }
}
