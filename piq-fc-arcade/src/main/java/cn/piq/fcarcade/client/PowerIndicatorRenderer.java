package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Tiny surface overlay only. No model replacement, bloom or world-light emission from consoles. */
public final class PowerIndicatorRenderer {
    private static ResourceLocation white;
    private PowerIndicatorRenderer() {}
    private static ResourceLocation white() {
        if (white == null) {
            var pixel = new DynamicTexture(1,1,false);
            pixel.getPixels().setPixelRGBA(0,0,0xffffffff); pixel.setFilter(false,false); pixel.upload();
            white = Minecraft.getInstance().getTextureManager().register("piq_indicator",pixel);
        }
        return white;
    }
    static void close() {
        if (white != null) Minecraft.getInstance().getTextureManager().release(white);
        white = null;
    }
    /** Caller pose is unscaled north-facing hardware space; coordinates are already model units / 16. */
    public static void face(PoseStack poses, MultiBufferSource buffers, int light, int overlay, boolean on,
                            double x0,double y0,double z0,double x1,double y1,double z1) {
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(white()));
        double ny=z1-z0,nz=-(y1-y0),length=Math.hypot(ny,nz);
        if (!(length>0) || !(x1>x0)) return;
        int color=on?0xff53ee69:0xff692724, packed=on?LightTexture.FULL_BRIGHT:light;
        double[][] points={{x0,y0,z0},{x0,y1,z1},{x1,y1,z1},{x1,y0,z0}};
        for(var p:points) out.addVertex(poses.last().pose(),(float)p[0],(float)p[1],(float)p[2])
                .setColor(color).setUv(.5f,.5f).setOverlay(overlay).setLight(packed)
                .setNormal(poses.last(),0,(float)(ny/length),(float)(nz/length));
    }
    static void console(HomeConsoleBlockEntity console,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        if (console.getBlockState().getBlock() instanceof SuborConsoleBlock) return;
        // Added lens at the user's marked lower-front corner of the FC shell.
        face(poses,buffers,light,overlay,console.visualPowered()||PrivateHomeClient.ownsConsole(console),11.9/16,1.18/16,1.196/16,12.17/16,1.45/16,1.196/16);
    }
    static void television(HomeTvBlockEntity tv,int turns,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        double x0,y0,z,x1,y1;
        var block=tv.getBlockState().getBlock();
        if(block instanceof RetroTvBlock model && UserTvLayout.supports(model.displayStyle())) {
            double[] lamp=UserTvLayout.lamp(model.displayStyle());x0=lamp[0];y0=lamp[1];z=lamp[2];x1=lamp[3];y1=lamp[4];
        }
        else if(block instanceof VintageTvBlock) {x0=2.82;y0=1.42;z=2.081;x1=3;y1=1.57;}
        else if(block instanceof LcdTvBlock) {x0=13.7;y0=1.16;z=5.856;x1=14.4;y1=1.36;}
        else if(block instanceof WideLcdTvBlock || block instanceof LargeLcdTvBlock) {x0=21.7;y0=1.16;z=5.856;x1=22.4;y1=1.36;}
        else {x0=2.32;y0=3.58;z=1.956;x1=2.6;y1=3.86;}
        var offset=HomeHardwareRenderLayout.tvOffset(turns,HomeTvStructure.centered(tv.getBlockState()));
        poses.pushPose();
        try {
            poses.translate(offset.x(),offset.y(),offset.z());
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90f*turns));poses.translate(-.5,0,-.5);
            face(poses,buffers,light,overlay,tv.powered()||PrivateHomeClient.ownsDisplay(tv),x0/16,y0/16,z/16,x1/16,y1/16,z/16);
        } finally {poses.popPose();}
    }
}
