// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.client;

import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.fcarcade.home.*;
import cn.piq.pvz.net.PvzNetwork;
import cn.piq.pvz.registry.PvzRegistries;
import cn.piq.pvz.runtime.PvzEngine;
import cn.piq.pvz.runtime.PvzJniRuntime;
import cn.piq.pvz.world.PvzBlockEntity;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@EventBusSubscriber(modid="piq_pvz",value=Dist.CLIENT)
public final class PvzClient implements PvzNetwork.Client {
    private static final Object OWNER=new Object();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"PvZ-Lifecycle");t.setDaemon(true);return t;});
    private static PvzNetwork.Open target;
    private static Object connection;
    private static PvzEngine runtime;
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static ByteBuffer uploadBuffer;
    private static double uploadMs;
    private static long uploads,uploadWindow=System.nanoTime();
    private static int displayFps;
    private static volatile int generation;
    private static final java.util.concurrent.atomic.AtomicReference<PvzEngine> pending=new java.util.concurrent.atomic.AtomicReference<>();
    private static boolean busy,ending;
    private static String notice="选择本机 main.pak 开始",lastPath="";
    @EventBusSubscriber(modid="piq_pvz",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->PvzNetwork.client(new PvzClient()));}
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(PvzRegistries.BOX_ENTITY.get(),PvzCableRenderer::new);}
    }
    @Override public boolean acceptsConnection(Object source){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source&&c.getConnection().isConnected();}
    @Override public void open(PvzNetwork.Open message){
        var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return;
        if(!message.equals(target)||connection!=mc.getConnection()){end();target=message;connection=mc.getConnection();}
        if(!valid()||!CabinetClientOwner.acquire(OWNER)){mc.player.displayClientMessage(Component.literal("设备连接无效或另一台设备正在使用输入"),false);return;}
        mc.setScreen(new PvzScreen());
    }
    public static boolean valid(){
        var mc=Minecraft.getInstance();var p=target;
        if(p==null||mc.level==null||mc.player==null||mc.getConnection()==null||mc.getConnection()!=connection||!mc.getConnection().getConnection().isConnected()
                ||!mc.level.dimension().location().equals(p.dimension())||!mc.player.isAlive()||mc.player.isSpectator()||!mc.player.isCreative()||!mc.player.hasPermissions(2)
                ||mc.player.distanceToSqr(p.console().getCenter())>64||!mc.level.hasChunkAt(p.console())||!mc.level.hasChunkAt(p.television()))return false;
        return mc.level.getBlockEntity(p.console()) instanceof PvzBlockEntity box&&mc.level.getBlockEntity(p.television()) instanceof HomeTvBlockEntity tv
                &&!box.isRemoved()&&!tv.isRemoved()&&p.consoleId().equals(box.hardwareId())&&p.televisionId().equals(tv.hardwareId())
                &&p.linkId().equals(box.linkId())&&p.linkId().equals(tv.linkId())&&p.television().equals(box.televisionPos())&&p.console().equals(tv.consolePos())
                &&HomeHardware.connected(mc.level,box,tv);
    }
    static void start(String path){
        if(busy||ending||runtime!=null||!valid())return;
        final Path file;try{file=Path.of(path.trim().replaceAll("^\"|\"$",""));}catch(Exception e){notice="路径无效";return;}
        lastPath=path;int attempt=++generation;busy=true;notice="正在准备 PvZ…";
        var mc=Minecraft.getInstance();Path root=mc.gameDirectory.toPath();var id=mc.player.getUUID();
        IO.execute(()->{
            PvzEngine result=null;String error="";
            try{result=new PvzJniRuntime(root,file,id);}catch(Exception e){error=e.getMessage();}
            PvzEngine loaded=result;String failure=error;
            if(loaded!=null)pending.set(loaded);
            mc.execute(()->{
                if(attempt!=generation||!valid()){if(loaded!=null&&pending.compareAndSet(loaded,null))IO.execute(loaded::close);return;}
                if(loaded!=null&&!pending.compareAndSet(loaded,null))return;
                busy=false;runtime=loaded;notice=loaded==null?"启动失败："+failure:"正在加载游戏…";
            });
        });
    }
    static void stop(){
        ++generation;boolean loading=busy;busy=false;PvzEngine old=runtime;runtime=null;releaseTexture();
        if(old!=null||loading){ending=true;notice="正在结束并写入本机进度…";IO.execute(()->{
            PvzEngine launching=pending.getAndSet(null);if(launching!=null)launching.close();if(old!=null)old.close();
            Minecraft.getInstance().execute(()->{ending=false;notice=old==null||old.error().isEmpty()?"已正常结束；进度由游戏自身保存":"退出未确认："+old.error();});
        });}
    }
    private static void releaseTexture(){if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);else if(texture!=null)texture.close();texture=null;textureId=null;if(uploadBuffer!=null)MemoryUtil.memFree(uploadBuffer);uploadBuffer=null;uploads=0;displayFps=0;uploadMs=0;uploadWindow=System.nanoTime();}
    private static void end(){stop();target=null;connection=null;CabinetClientOwner.release(OWNER);}
    static void releaseInput(){if(runtime!=null){runtime.input(0,0,0,0,false);runtime.key(false,8,0);}}
    static void input(int pad,int x,int y,int buttons,boolean pointer){if(runtime!=null&&valid())runtime.input(pad,x,y,buttons,pointer);}
    static void key(boolean down,int key,int character){if(runtime!=null&&valid())runtime.key(down,key,character);}
    static boolean ready(){return runtime!=null&&runtime.ready();}
    static boolean running(){return runtime!=null||busy||ending;}
    static String lastPath(){return lastPath;}
    static String status(){return runtime!=null?runtime.status()+" · 显示 "+displayFps+" FPS · 上传 "+String.format(java.util.Locale.ROOT,"%.1f",uploadMs)+" ms":notice;}
    static ResourceLocation texture(){return textureId;}
    static void menuRemoved(){releaseInput();}
    public static boolean ownsDisplay(HomeTvBlockEntity tv){return ready()&&textureId!=null&&valid()&&tv.getLevel()==Minecraft.getInstance().level&&tv.getBlockPos().equals(target.television())&&tv.powered();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){end();}
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){end();IO.shutdown();try{IO.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){maintain(false);}
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){maintain(true);}
    private static void maintain(boolean upload){
        if(target==null)return;var mc=Minecraft.getInstance();
        if(!valid()){end();if(mc.screen instanceof PvzScreen)mc.setScreen(null);return;}
        if(runtime==null)return;
        if(runtime.finished()){notice=runtime.error().isEmpty()?"运行已结束":runtime.error();runtime=null;releaseTexture();return;}
        var tv=(HomeTvBlockEntity)mc.level.getBlockEntity(target.television());
        runtime.pause(!mc.isWindowActive()||!tv.powered()||mc.screen!=null&&!(mc.screen instanceof PvzScreen)||mc.isPaused());
        runtime.volume(tv.powered()?mc.options.getSoundSourceVolume(SoundSource.MASTER)*.7f:0);
        if(!upload)return;PvzEngine current=runtime;byte[] data=current.poll();if(data==null)return;
        try{
            RenderSystem.assertOnRenderThread();long began=System.nanoTime();
            if(texture==null){texture=new DynamicTexture(800,600,false);texture.setFilter(false,false);textureId=mc.getTextureManager().register("pvz_preview",texture);uploadBuffer=MemoryUtil.memAlloc(800*600*4);}
            // RGBA already matches GL. One bulk transfer instead of 480,000 Java/native pixel calls.
            uploadBuffer.clear();uploadBuffer.put(data).flip();RenderSystem.bindTexture(texture.getId());
            GlStateManager._pixelStore(3314,0);GlStateManager._pixelStore(3316,0);GlStateManager._pixelStore(3315,0);GlStateManager._pixelStore(3317,4);
            GlStateManager._texSubImage2D(3553,0,0,0,800,600,6408,5121,MemoryUtil.memAddress(uploadBuffer));
            double took=(System.nanoTime()-began)/1e6;uploadMs=uploadMs==0?took:uploadMs*.9+took*.1;uploads++;
            long now=System.nanoTime();if(now-uploadWindow>=1_000_000_000L){displayFps=(int)Math.round(uploads/((now-uploadWindow)/1e9));uploads=0;uploadWindow=now;}
        }finally{current.releaseFrame(data);}
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){if(textureId!=null&&ready()&&valid())HomeVideoDisplay.render(event,PvzBlockEntity.SYSTEM_ID,target.console(),target.consoleId(),target.television(),target.televisionId(),target.linkId(),textureId,4.0/3);}
}
