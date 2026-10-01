// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.nativearcade.bridge.NativeJniMediaSession;
import cn.piq.nativearcade.events.*;
import cn.piq.nativearcade.layout.*;
import cn.piq.nativearcade.world.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import cn.piq.fcarcade.client.cabinet.CabinetImmersiveInput;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.fcarcade.client.cabinet.CabinetUseGuard;
import cn.piq.fcarcade.client.cabinet.CabinetGameSelection;
import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.retro.client.GamepadInput;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** The native library is NEVER loaded here: all core work is owned by a killable helper. */
@EventBusSubscriber(modid="piq_native_arcade",value=Dist.CLIENT)
public final class NativeArcadeClient {
    private static final ExecutorService STARTER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"PIQ-Native-Launch");t.setDaemon(true);return t;});
    private static NativeJniMediaSession session;
    private static NativeArcadeAudio audio;
    private static BlockPos anchor;
    private static UUID identity;
    private static ResourceLocation dimension;
    private static Object sessionConnection;
    private static CabinetRomBindings.Key selectionKey;
    private static boolean configureSelection;
    private static volatile int generation;
    private static volatile boolean shuttingDown;
    private static final AtomicReference<NativeJniMediaSession> PENDING=new AtomicReference<>();
    private static boolean launching;
    private static boolean playing,announced;
    private static final CabinetImmersiveInput INPUT=new CabinetImmersiveInput();
    private static final Object INPUT_OWNER=new Object();
    private static int mixedInputMask;
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static float contentAspect=4F/3F;
    private static int rotation;
    private NativeArcadeClient(){}
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){shuttingDown=true;stop(null);STARTER.shutdownNow();}
    @EventBusSubscriber(modid="piq_native_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(()->{
            NativeCabinetRenderer.setVideoRenderer(NativeArcadeClient::render);
            cn.piq.fcarcade.client.cabinet.CabinetClientBackends.register(
                    cn.piq.nativearcade.NativeArcadeMod.BACKEND_ID,new NativeCabinetBackend());
        });}
    }
    static boolean supported(){var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        return !shuttingDown&&System.getProperty("os.name","").startsWith("Windows")&&System.getProperty("os.arch","").matches("amd64|x86_64")
                &&server!=null&&!server.isPublished()&&mc.player!=null&&mc.player.isAlive()&&!mc.player.isSpectator()&&mc.level!=null;}
    @SubscribeEvent public static void use(NativeCabinetUseEvent e){
        var mc=Minecraft.getInstance();if(mc.getSingleplayerServer()!=e.level().getServer()||e.level().getServer().isPublished())return;
        e.markHandled();var playerId=e.player().getUUID();var dim=e.level().dimension().location();
        var sourceServer=e.level().getServer();var sourceConnection=mc.getConnection();
        boolean configure=e.player().isShiftKeyDown()&&e.player().getMainHandItem().isEmpty();
        mc.execute(()->{if(mc.getSingleplayerServer()==sourceServer&&mc.getConnection()==sourceConnection&&mc.player!=null&&mc.player.getUUID().equals(playerId)&&mc.level!=null&&mc.level.dimension().location().equals(dim))openAt(e.anchor(),e.assemblyId(),configure);});
    }
    @SubscribeEvent public static void closed(NativeCabinetClosedEvent e){
        var mc=Minecraft.getInstance();if(mc.getSingleplayerServer()!=e.level().getServer())return;
        var sourceServer=e.level().getServer();var sourceConnection=mc.getConnection();
        mc.execute(()->{if(mc.getSingleplayerServer()==sourceServer&&mc.getConnection()==sourceConnection&&e.anchor().equals(anchor)&&e.assemblyId().equals(identity)&&e.level().dimension().location().equals(dimension))stop("机柜已卸载或拆除");});
    }
    public static void openAt(BlockPos pos,UUID id){
        openAt(pos,id,false);
    }
    private static void openAt(BlockPos pos,UUID id,boolean configure){
        if(CabinetUseGuard.blocked())return;
        if(cn.piq.fcarcade.client.ClientArcadeEvents.isControlling()){toast("请先退出正在控制的 FC 游戏");return;}
        var mc=Minecraft.getInstance();if(!supported()){toast("原生街机首版仅支持 Windows x64 未开放局域网的单人世界");return;}
        if(mc.screen!=null)return;
        if(!matches(pos,id)){toast("机柜尚未完整同步，请稍后右键");return;}
        if(playing){
            if(pos.equals(anchor)&&id.equals(identity)&&mc.level.dimension().location().equals(dimension)){
                CabinetUseGuard.suppressWhileHeld();stop("已结束街机；再次右键启动，Shift 右键配置游戏");
            }else toast("请先右键正在使用的街机结束本局");
            return;
        }
        stop(null);
        if(!CabinetClientOwner.acquire(INPUT_OWNER)){toast("请先退出正在使用的其他街机");return;}
        anchor=pos.immutable();identity=id;dimension=mc.level.dimension().location();
        selectionKey=CabinetGameSelection.key(dimension,id,cn.piq.nativearcade.NativeArcadeMod.BACKEND_ID).orElse(null);
        sessionConnection=mc.getConnection();configureSelection=configure;
        if(selectionKey==null){stop("世界连接尚未就绪，请稍后重试");return;}
        if(configure)mc.setScreen(new NativeArcadeSetupScreen());
        else startGame(null,false);
    }
    private static boolean matches(BlockPos pos,UUID id){var mc=Minecraft.getInstance();return mc.level!=null&&mc.player!=null&&pos!=null&&id!=null
        &&mc.level.hasChunkAt(pos)&&mc.level.getBlockEntity(pos) instanceof NativeCabinetBlockEntity be&&be.installed()&&id.equals(be.assemblyId())
        &&NativeCabinetStructure.complete(mc.level,pos)&&mc.player.distanceToSqr(pos.getX()+1,pos.getY()+1,pos.getZ()+.5)<64;}
    static boolean current(){var mc=Minecraft.getInstance();return supported()&&mc.getConnection()==sessionConnection&&!cn.piq.fcarcade.client.ClientArcadeEvents.isControlling()&&dimension!=null&&mc.level.dimension().location().equals(dimension)&&matches(anchor,identity);}
    static Path dataRoot(){return cn.piq.retro.storage.ConsoleStorage.root(Minecraft.getInstance().gameDirectory.toPath()).resolve("piq-native-arcade");}
    static void start(Path rom){
        if(!configureSelection||!(Minecraft.getInstance().screen instanceof NativeArcadeSetupScreen))return;
        startGame(rom,true);
    }
    private static void startGame(Path chosen,boolean remember){
        if(launching||session!=null||!current())return;
        var mc=Minecraft.getInstance();var key=selectionKey;int token=++generation;launching=true;configureSelection=false;
        playing=true;announced=false;INPUT.reset();mc.setScreen(null);
        toast("街机启动中… 可自由移动和转动视角；右键本机结束");
        try{STARTER.execute(()->{NativeJniMediaSession opened=null;
            try{
                if(shuttingDown||token!=generation)return;
                Path rom=remember?chosen:CabinetGameSelection.load(key).orElseThrow(()->new java.io.IOException("此机柜尚未配置游戏，请 Shift 空手右键选择"));
                CabinetGameSelection.validate(rom,Set.of(".zip"),Set.of("neogeo.zip","qsound_hle.zip"));
                if(shuttingDown||token!=generation)return;
                if(remember)CabinetGameSelection.remember(key,rom);
                opened=new NativeJniMediaSession(dataRoot().resolve("runtime"),rom);var ready=opened;
                if(shuttingDown||token!=generation){ready.close();return;}PENDING.set(ready);
                if(shuttingDown||token!=generation){PENDING.compareAndSet(ready,null);ready.close();return;}
                mc.execute(()->{PENDING.compareAndSet(ready,null);if(token!=generation||!current()||!playing){ready.close();return;}
                    session=ready;launching=false;audio=new NativeArcadeAudio();});
            }catch(Exception failure){if(opened!=null)opened.close();mc.execute(()->{if(token==generation)fail("原生核心启动失败："+failure.getMessage(),failure);});}
        });}catch(RejectedExecutionException failure){fail("街机启动线程已关闭，请重新启动客户端",failure);}
    }
    static boolean running(){return session!=null&&session.isReady();}
    static void input(int p1,int p2){if(session!=null)session.offerInput(p1,p2);}
    static void clearInput(){if(session!=null)session.clearInput();}
    private static void syncInput(){
        var mc=Minecraft.getInstance();
        boolean active=playing&&current()&&running()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();
        cn.piq.retro.client.KeyboardInput.attach(INPUT_OWNER,cn.piq.retro.client.KeyboardConfig.Profile.ARCADE,
                () -> cn.piq.retro.client.KeyboardConfig.presetKeys(cn.piq.retro.client.KeyboardConfig.Profile.ARCADE,cn.piq.retro.client.KeyboardConfig.Preset.LEGACY).stream().map(k->new int[]{k}).toArray(int[][]::new),
                () -> playing&&current()&&running()&&cn.piq.retro.input.InputOwnership.owns(INPUT_OWNER)&&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected(),NativeArcadeClient::releaseKeyboard,NativeArcadeClient::syncInput);
        var keyboard=cn.piq.retro.client.KeyboardInput.poll(INPUT_OWNER,0,active);
        active=active&&keyboard.enabled()&&keyboard.armed();
        int mask=GamepadInput.mix(INPUT_OWNER,GamepadInput.ProfileKind.ARCADE,keyboard.mask(),active);
        if(active&&mask!=mixedInputMask){mixedInputMask=mask;input(mask,0);}
        if(!active){GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;}
    }
    private static void releaseKeyboard(){INPUT.reset();GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;clearInput();}
    @SubscribeEvent public static void key(InputEvent.Key event){
        if(!playing)return;syncInput();
    }
    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event){
        if(!playing||event.getNewScreen()==null)return;
        cn.piq.retro.client.KeyboardInput.pause(INPUT_OWNER);releaseKeyboard();
    }
    static void stop(String reason){
        generation++;launching=false;var old=session;session=null;if(old!=null){old.clearInput();old.close();}
        playing=false;announced=false;INPUT.reset();
        GamepadInput.release(INPUT_OWNER);mixedInputMask=0;
        cn.piq.retro.client.KeyboardInput.release(INPUT_OWNER);
        CabinetClientOwner.release(INPUT_OWNER);
        var pending=PENDING.getAndSet(null);if(pending!=null)pending.close();
        if(audio!=null){audio.close();audio=null;}
        if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);else if(texture!=null)texture.close();
        texture=null;textureId=null;anchor=null;identity=null;dimension=null;
        selectionKey=null;sessionConnection=null;configureSelection=false;
        if(Minecraft.getInstance().screen instanceof NativeArcadePlayScreen)Minecraft.getInstance().setScreen(null);
        if(reason!=null&&!reason.isBlank())toast(reason);
    }
    private static void fail(String reason,Throwable failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("街机",reason,failure);stop(null);}
    private static void toast(String text){cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("街机",text);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(anchor!=null&&(!current()||(!playing&&!(mc.screen instanceof NativeArcadeSetupScreen)))){stop("已退出原生街机");return;}
        syncInput();
        if(playing&&running()&&!announced){announced=true;}
        if(session==null)return;
        if(session.error()!=null){stop(session.error());return;}
        if(audio!=null)audio.gain(.60F*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS));
    }
    /** One world-render pump; cabinet BER visibility must not control audio consumption. */
    @SubscribeEvent public static void frame(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_ENTITIES||session==null||!current())return;
        upload();
    }
    private static void upload(){
        syncInput();
        if(session==null)return;NativeJniMediaSession.Frame f=session.pollFrame();if(f==null)return;
        if(texture==null||texture.getPixels().getWidth()!=f.width()||texture.getPixels().getHeight()!=f.height()){
            if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
            texture=new DynamicTexture(f.width(),f.height(),false);texture.setFilter(false,false);
            textureId=ResourceLocation.fromNamespaceAndPath("piq_native_arcade","screen");Minecraft.getInstance().getTextureManager().register(textureId,texture);
        }
        NativeImage image=texture.getPixels();int[] pixels=f.abgr();
        for(int y=0;y<f.height();y++)for(int x=0;x<f.width();x++)image.setPixelRGBA(x,y,pixels[y*f.width()+x]|0xff000000);
        texture.upload();rotation=f.rotation();contentAspect=NativeVideoPresentation.displayAspect(f.displayAspect(),rotation);
        if(audio!=null)audio.offer(f.pcm48k());
    }
    private static void render(NativeCabinetBlockEntity cabinet,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        if(session==null||!cabinet.getBlockPos().equals(anchor)||!cabinet.assemblyId().equals(identity)||!current())return;
        if(textureId==null)return;
        var quad=NativeCabinetLayout.frame(NativeCabinetRenderer.turns(cabinet),contentAspect);
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(textureId));var pose=poses.last();
        var points=new cn.piq.fcarcade.layout.RocketArcadeGeometry.Point[]{quad.lowerMaxX(),quad.lowerMinX(),quad.upperMinX(),quad.upperMaxX()};
        float[][] uv={{0,1},{1,1},{1,0},{0,0}};
        for(int i=0;i<4;i++){var p=points[i];var t=NativeVideoPresentation.textureUv(uv[i][0],uv[i][1],rotation);var n=quad.normal();
            out.addVertex(pose,(float)p.x(),(float)p.y(),(float)p.z()).setColor(255,255,255,255).setUv(t[0],t[1])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose,(float)n.x(),(float)n.y(),(float)n.z());}
    }
}
