package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** Label-only view of the FC cover atlas; no second download, decoder or texture cache. */
public final class ContentCardCoverRenderer {
    private ContentCardCoverRenderer(){}
    public static void north(String hash,PoseStack poses,MultiBufferSource buffers,int light,int overlay,
                             double x0,double y0,double x1,double y1,double z){
        var texture=ClientCartridgeCovers.texture(hash);if(texture==null)return;
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        // FC's shared composed atlas defines the label at [32,32,544,288).
        float u0=32/1024f,u1=544/1024f,v0=32/1024f,v1=288/1024f;
        double[][] vertices={{x1,y0,u0,v1},{x1,y1,u0,v0},{x0,y1,u1,v0},{x0,y0,u1,v1}};
        for(var v:vertices)out.addVertex(poses.last().pose(),(float)v[0],(float)v[1],(float)z)
                .setColor(255,255,255,255).setUv((float)v[2],(float)v[3]).setOverlay(overlay).setLight(light).setNormal(poses.last(),0,0,-1);
    }
}
