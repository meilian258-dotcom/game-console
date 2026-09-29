package cn.piq.flashbox.client;

import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.flashbox.net.FlashBoxNetwork;
import cn.piq.flashbox.runtime.FlashRuntime;
import cn.piq.flashbox.runtime.PlaybackPolicy;
import cn.piq.flashbox.world.FlashBoxBlockEntity;
import java.nio.file.Path;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@EventBusSubscriber(modid="piq_flash_box",value=Dist.CLIENT)
public final class FlashBoxClient implements FlashBoxNetwork.Client {
    private static final Object OWNER=new Object();
    private static final ExecutorService STARTER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"FlashBox-Start");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static cn.piq.flashbox.runtime.StartTicket pending;
    private static FlashBoxNetwork.Open target;
    private static Object connection;
    private static FlashRuntime runtime;
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static boolean busy,paused;
    private static int generation;
    private static String lastPath="";
    private static String notice="选择本机 SWF；文件不会上传。此版未接入跨玩家双人及省流同步。";
    @EventBusSubscriber(modid="piq_flash_box",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(()->FlashBoxNetwork.client(new FlashBoxClient()));
        }
        @SubscribeEvent public static void renderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(cn.piq.flashbox.registry.FlashBoxRegistries.BOX_ENTITY.get(),FlashBoxCableRenderer::new);
        }
    }
    @Override public boolean acceptsConnection(Object source) {
        var c=Minecraft.getInstance().getConnection();return c!=null && c.getConnection()==source && c.getConnection().isConnected();
    }
    @Override public void open(FlashBoxNetwork.Open grant) {
        var mc=Minecraft.getInstance();
        if (mc.player==null || mc.level==null || mc.getConnection()==null) return;
        // Incoming menu messages never choose a file or launch an executable.
        if(grant.equals(target) && mc.getConnection()==connection && valid()) {
            if(mc.screen instanceof FlashBoxScreen)return;
            if(FlashWorldInput.active()){FlashWorldInput.preview();return;}
            if(!CabinetClientOwner.acquire(OWNER)) {
                mc.player.displayClientMessage(Component.literal("另一个游戏正在占用输入；电视会继续本机播放。"),false);return;
            }
            mc.setScreen(new FlashBoxScreen());return;
        }
        if(mc.screen instanceof FlashBoxScreen)mc.setScreen(null);
        endSession("已切换播放盒");lastPath="";
        target=grant;connection=mc.getConnection();
        if (!valid() || !CabinetClientOwner.acquire(OWNER)) {
            target=null;connection=null;
            mc.player.displayClientMessage(Component.literal("播放盒连接无效或已有其他游戏占用输入。"),false);return;
        }
        mc.setScreen(new FlashBoxScreen());
    }
    public static boolean valid() {
        var mc=Minecraft.getInstance();var p=target;
        if (p==null || mc.level==null || mc.player==null || mc.getConnection()!=connection || !mc.getConnection().getConnection().isConnected()
                || !mc.level.dimension().location().equals(p.dimension()) || !mc.player.isAlive() || mc.player.isSpectator()
                || !mc.player.isCreative() || !mc.player.hasPermissions(2)
                || mc.player.distanceToSqr(p.console().getCenter())>64 || !mc.level.hasChunkAt(p.console()) || !mc.level.hasChunkAt(p.television())) return false;
        return mc.level.getBlockEntity(p.console()) instanceof FlashBoxBlockEntity box
                && mc.level.getBlockEntity(p.television()) instanceof HomeTvBlockEntity tv
                && !box.isRemoved() && !tv.isRemoved() && p.consoleId().equals(box.hardwareId()) && p.televisionId().equals(tv.hardwareId())
                && p.linkId().equals(box.linkId()) && p.linkId().equals(tv.linkId())
                && p.television().equals(box.televisionPos()) && p.console().equals(tv.consolePos())
                && cn.piq.fcarcade.home.HomeHardware.connected(mc.level,box,tv);
    }
    public static boolean ownsDisplay(HomeTvBlockEntity television) {
        // No server field is modified: this only hides the idle overlay for this client's active picture.
        return textureId!=null && runtime!=null && runtime.ready() && target!=null && valid()
                && television.getLevel()==Minecraft.getInstance().level && television.getBlockPos().equals(target.television()) && television.powered();
    }
    static void start(String localFile) {
        if (runtime!=null || busy || !valid()) { notice="先停止旧预览并确认视频连接有效。";return; }
        final Path file;
        try { file=Path.of(localFile.trim());if(!file.isAbsolute())throw new IllegalArgumentException(); }
        catch (Exception e) { notice="请粘贴本机 SWF 的完整路径。";return; }
        busy=true;notice="正在校验运行环境并加载本机 SWF…";
        int attempt=++generation;Path directory=Minecraft.getInstance().gameDirectory.toPath();
        var ticket=new cn.piq.flashbox.runtime.StartTicket();pending=ticket;
        try { STARTER.execute(()-> {
            FlashRuntime result=null;String error=null;
            try {
                if(ticket.cancelled())return;
                result=new FlashRuntime(directory,file,ticket::cancelled);
                if(!ticket.register(result))return;
            } catch (Exception e) { error=e.getMessage(); }
            FlashRuntime loaded=result;String failure=error;
            if(ticket.cancelled()){if(loaded!=null)loaded.close();return;}
            Minecraft.getInstance().execute(()-> {
                if (ticket.cancelled() || attempt!=generation || !valid() || !(Minecraft.getInstance().screen instanceof FlashBoxScreen)) {
                    if (loaded!=null) loaded.close();return;
                }
                busy=false;pending=null;
                if (loaded==null) notice="启动失败："+(failure==null?"未知错误":failure);
                else { runtime=loaded;paused=false;notice="运行器已启动，等待游戏首帧…"; }
            });
        }); } catch(RejectedExecutionException e) { ticket.cancel();pending=null;busy=false;notice="旧运行器仍在收尾，请稍后重试。"; }
    }
    static boolean busy() { return busy; }
    static boolean active() { return runtime!=null; }
    static boolean canControlTelevision() {
        var mc=Minecraft.getInstance();
        return runtime!=null && runtime.ready() && textureId!=null && valid()
                && mc.level.getBlockEntity(target.television()) instanceof HomeTvBlockEntity tv && tv.powered();
    }
    static boolean acquireInput() { return CabinetClientOwner.acquire(OWNER); }
    static boolean ownsInput() { return cn.piq.retro.input.InputOwnership.owns(OWNER); }
    static void releaseInput() { CabinetClientOwner.release(OWNER); }
    static void releaseRuntimeInput() { if(runtime!=null){runtime.keys(0,0);runtime.mouse(0,0,false);} }
    static int captureFps() { return runtime==null?0:runtime.captureFps(); }
    static String lastPath() { return lastPath; }
    static void rememberPath(String path) { lastPath=path; }
    static String status() { return runtime==null?notice:(paused?"已暂停 · ":"")+runtime.status()+" · 画面接收约 "+runtime.captureFps()+" FPS"; }
    static ResourceLocation texture() { return textureId; }
    static void keys(int p1,int p2) { if(runtime!=null&&!paused)runtime.keys(p1,p2); }
    static void pointer(int x,int y,boolean down) { if(runtime!=null&&!paused)runtime.mouse(x,y,down); }
    static void pause(boolean value) {
        if (runtime==null || paused==value) return;
        paused=value;if(value)runtime.pause();else runtime.resume();
    }
    static void stop(String reason) {
        FlashWorldInput.stop();
        ++generation;busy=false;notice=reason;
        if(pending!=null)pending.cancel();pending=null;
        if(runtime!=null)runtime.close();runtime=null;paused=false;
        if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
        else if(texture!=null)texture.close();
        texture=null;textureId=null;
    }
    static void menuRemoved() {
        // Never retain a held key/mouse or claim the world's keyboard after closing the controls.
        releaseRuntimeInput();
        if(!FlashWorldInput.active())releaseInput();
        if(!PlaybackPolicy.keepAfterMenuClose(busy,runtime!=null && runtime.ready())) {
            endSession("未就绪的启动已取消；此原型不提供存档保障。");
        }
    }
    private static void endSession(String reason) {
        stop(reason);
        CabinetClientOwner.release(OWNER);target=null;connection=null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { endSession("已离开世界"); }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) { endSession("正在退出游戏");STARTER.shutdownNow(); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { maintain(false); }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) { maintain(true); }
    private static void maintain(boolean uploadFrame) {
        FlashWorldInput.maintain();
        if(target==null)return;
        var mc=Minecraft.getInstance();
        if (!valid()) {
            endSession("播放盒连接、距离或权限已失效，已停止");
            if(mc.screen instanceof FlashBoxScreen)mc.setScreen(null);
            return;
        }
        pause(PlaybackPolicy.pause(mc.isWindowActive(),mc.screen!=null && !(mc.screen instanceof FlashBoxScreen),mc.isPaused()));
        // Consume the latest image on the render clock, not the 20 Hz world-tick clock.
        if (runtime==null || !uploadFrame) return;
        var frame=runtime.poll();if(frame==null)return;
        if(texture==null) {
            texture=new DynamicTexture(640,480,false);texture.setFilter(false,false);
            textureId=mc.getTextureManager().register("flash_box_preview",texture);
        }
        for(int y=0;y<480;y++)for(int x=0;x<640;x++)texture.getPixels().setPixelRGBA(x,y,frame.abgr()[y*640+x]);
        texture.upload();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (target==null || textureId==null || !valid() || runtime==null || !runtime.ready()) return;
        HomeVideoDisplay.render(event,FlashBoxBlockEntity.SYSTEM_ID,target.console(),target.consoleId(),
                target.television(),target.televisionId(),target.linkId(),textureId,4.0/3);
    }
}
