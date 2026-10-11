package cn.piq.replaycompat.client;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.capture.CaptureHooks;
import cn.piq.replaycompat.replay.ReplayBridge;
import cn.piq.fcarcade.api.replay.ReplayPresentation;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Renders only recorded world-space quads. No block lookup, core, input, save or server call. */
@EventBusSubscriber(modid="piq_replay_compat",value=Dist.CLIENT)
public final class ReplayScreens {
    private record Texture(ResourceLocation id,DynamicTexture value,long version){}
    private static final ScreenPresentationCache presentation=new ScreenPresentationCache();
    private static final Texture[] textures=new Texture[ScreenPresentationCache.MAX_SLOTS];
    // The public material provider memoizes by ID; use a bounded set of texture IDs.
    private static final ResourceLocation[] ids=new ResourceLocation[ScreenPresentationCache.MAX_SLOTS];
    static { for(int slot=0;slot<ids.length;slot++)ids[slot]=ResourceLocation.fromNamespaceAndPath("piq_replay_compat","screen/"+slot); }
    private static String lastStatus="";
    private ReplayScreens(){}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void begin(RenderLevelStageEvent event){CaptureHooks.beginWorld(event);}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void end(RenderLevelStageEvent event){
        CaptureHooks.endWorld(event);
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        ReplayBridge.tickAudio();
        if(!ScreenReplay.playing()){clear();return;}
        var mc=Minecraft.getInstance();if(mc.level==null){clear();return;}
        String dimension=mc.level.dimension().location().toString();
        long time=ReplayBridge.playbackTime();
        var view=ReplayBridge.viewAt(time);var frustum=event.getFrustum();
        // Use this render pass's camera/frustum, including Replay/export cameras. No live-player
        // watching radius, block/entity lookup, or visibility inference from a previous frame.
        var plan=presentation.select(view.frames(),view.activeSources(),dimension,bounds->frustum==null||frustum.isVisible(
                new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ())));
        boolean[] retained=new boolean[textures.length];
        for(var image:plan.retainedImages())retained[image.slot()]=true;
        for(int slot=0;slot<textures.length;slot++)if(!retained[slot])drop(slot);
        if(plan.draws().isEmpty())return;
        var buffers=mc.renderBuffers().bufferSource();var camera=event.getCamera().getPosition();
        for(var image:plan.retainedImages()){
            boolean drawn=false;Texture cached=null;
            for(var draw:plan.draws()){
                if(draw.image()!=image)continue;
                if(cached==null)cached=texture(image);
                var type=ReplayPresentation.screenMaterial(cached.id());var out=buffers.getBuffer(type);
                var vertices=draw.frame().vertices();
                // Geometry always comes from this exact selected frame, not the cached image.
                for(int base=0;base<vertices.size();base+=4){
                    var a=vertices.get(base);var b=vertices.get(base+1);var c0=vertices.get(base+2);
                    var normal=new org.joml.Vector3f((float)(b.x()-a.x()),(float)(b.y()-a.y()),(float)(b.z()-a.z()));
                    normal.cross((float)(c0.x()-a.x()),(float)(c0.y()-a.y()),(float)(c0.z()-a.z()));
                    if(normal.lengthSquared()>1.0e-12f)normal.normalize();else normal.set(0,0,1);
                    for(int i=base;i<base+4;i++){
                        var v=vertices.get(i);int color=v.color();
                        out.addVertex(event.getPoseStack().last(),(float)(v.x()-camera.x),(float)(v.y()-camera.y),(float)(v.z()-camera.z))
                                .setColor((color>>>16)&255,(color>>>8)&255,color&255,(color>>>24)&255).setUv(v.u(),v.v())
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(normal.x,normal.y,normal.z);
                    }
                }
                drawn=true;
            }
            if(drawn)buffers.endBatch(ReplayPresentation.screenMaterial(cached.id()));
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        ReplayBridge.tickAudio();
        if(!ScreenReplay.playing())clear();
        String status=ReplayBridge.status();
        if(ScreenReplay.playing()||ReplayBridge.recording())status+="；"+cn.piq.replaycompat.audio.AudioBridge.status();
        var mc=Minecraft.getInstance();
        if(mc.level!=null&&!status.equals(lastStatus)){
            lastStatus=status;
            String readable=status.replaceAll("[\\p{Cntrl}§]", " ");
            if(readable.length()>120)readable=readable.substring(0,120)+"…（完整信息见日志）";
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("方块电玩 Replay："+readable),false);
        }
    }
    private static Texture texture(ScreenPresentationCache.Image content){
        int slot=content.slot();Texture cached=textures[slot];ScreenFrame frame=content.pixels();
        if(cached!=null&&cached.version()==content.version())return cached;
        if(cached==null||cached.value().getPixels()==null||cached.value().getPixels().getWidth()!=frame.width()
                ||cached.value().getPixels().getHeight()!=frame.height()){
            drop(slot);var pixels=new NativeImage(frame.width(),frame.height(),false);
            DynamicTexture value=null;
            try{
                fill(pixels,frame);value=new DynamicTexture(pixels); // Upload populated pixels once.
                value.setFilter(false,false);Minecraft.getInstance().getTextureManager().register(ids[slot],value);
                cached=new Texture(ids[slot],value,content.version());textures[slot]=cached;return cached;
            }catch(RuntimeException|Error failure){if(value!=null)value.close();else pixels.close();throw failure;}
        }
        fill(cached.value().getPixels(),frame);cached.value().upload();
        // Publish the physical version only after upload succeeded; a failed upload is retried.
        cached=new Texture(cached.id(),cached.value(),content.version());textures[slot]=cached;return cached;
    }
    private static void fill(NativeImage image,ScreenFrame frame){
        int pixel=0;
        for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++)image.setPixelRGBA(x,y,frame.pixelAbgr(pixel++));
    }
    private static void drop(int slot){var texture=textures[slot];textures[slot]=null;
        if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture.id());
    }
    private static void clear(){presentation.clear();for(int slot=0;slot<textures.length;slot++)drop(slot);}
}
