package cn.piq.computer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Small depth-tested overlay on the actual TV image, not a GUI or captured system cursor. */
final class ComputerPointerDisplay {
    private static ResourceLocation white;
    static void render(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        var d=ComputerWorldInput.cursorDisplay();if(d==null)return;
        var mc=Minecraft.getInstance();
        if(white==null){var t=new DynamicTexture(1,1,false);t.getPixels().setPixelRGBA(0,0,-1);t.setFilter(false,false);t.upload();white=mc.getTextureManager().register("computer_cursor",t);}
        var pose=e.getPoseStack();var camera=e.getCamera().getPosition();pose.pushPose();
        try{
            pose.translate(-camera.x,-camera.y,-camera.z);
            var buffers=mc.renderBuffers().bufferSource();var type=RenderType.entityCutoutNoCull(white);var out=buffers.getBuffer(type);
            int x=ComputerWorldInput.cursorX(),y=ComputerWorldInput.cursorY();
            rect(out,pose.last(),d,x-6,y-2,x+7,y+3,0x101010,.004);
            rect(out,pose.last(),d,x-2,y-6,x+3,y+7,0x101010,.004);
            rect(out,pose.last(),d,x-5,y-1,x+6,y+2,0xffffff,.0043);
            rect(out,pose.last(),d,x-1,y-5,x+2,y+6,0xffffff,.0043);
            buffers.endBatch(type);
        }finally{pose.popPose();}
    }
    private static void rect(com.mojang.blaze3d.vertex.VertexConsumer out,com.mojang.blaze3d.vertex.PoseStack.Pose pose,ComputerWorldInput.Display d,int x0,int y0,int x1,int y1,int rgb,double lift){
        var n=d.surface().image().normal();var normal=new Vec3(n.x(),n.y(),n.z());
        for(var p:new int[][]{{x0,y0},{x0,y1},{x1,y1},{x1,y0}}){
            var v=ComputerWorldInput.point(d,Math.clamp(p[0]/639.0,0,1),Math.clamp(p[1]/479.0,0,1)).add(normal.scale(lift));
            out.addVertex(pose,(float)v.x,(float)v.y,(float)v.z).setColor((rgb>>16)&255,(rgb>>8)&255,rgb&255,255).setUv(.5f,.5f).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose,(float)n.x(),(float)n.y(),(float)n.z());
        }
    }
    static void clear(){if(white!=null)Minecraft.getInstance().getTextureManager().release(white);white=null;}
}
