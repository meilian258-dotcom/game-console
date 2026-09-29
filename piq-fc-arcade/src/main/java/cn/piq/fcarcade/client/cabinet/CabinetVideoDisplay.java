package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.cabinet.CabinetTarget;
import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Presentation only: caller owns texture upload, authorization, playback, input, and resource cleanup. */
public final class CabinetVideoDisplay {
    private CabinetVideoDisplay() {}

    public static boolean visible(net.minecraft.world.phys.Vec3 camera,CabinetTarget target){
        var level=Minecraft.getInstance().level;
        return camera!=null&&target!=null&&level!=null&&target.matches(level)
            &&CabinetClientSettings.rules().visible(camera.distanceToSqr(target.anchor().getCenter()));
    }

    public static void render(RenderLevelStageEvent event,CabinetTarget target,ResourceLocation texture,
                              double rawAspect,int rotation) {
        var minecraft=Minecraft.getInstance();
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||texture==null||target==null
                ||minecraft.level==null||!target.matches(minecraft.level))return;
        if(!(minecraft.level.getBlockEntity(target.anchor()) instanceof LegacyFcArcadeBlockEntity cabinet)
                ||CabinetBackends.NES.equals(cabinet.cabinetBackend()))return; // Never draw over the original NES path.
        if(!CabinetClientSettings.rules().visible(event.getCamera().getPosition().distanceToSqr(target.anchor().getCenter())))return;
        var facing=cabinet.getBlockState().getValue(FcArcadeBlock.FACING);
        boolean compact=cabinet instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dualCabinet&&dualCabinet.compactFootprint();
        boolean portrait=cabinet instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity;
        var glass=portrait?cn.piq.fcarcade.layout.PortraitCabinetGeometry.screen(0):target.dual()?cn.piq.fcarcade.layout.DualCabinetGeometry.screen(0,compact):RocketArcadeGeometry.screen(0);
        rawAspect=cabinet.displayProfile().aspect().rawAspect(rawAspect,rotation,glass.width()/glass.height());
        final CabinetVideoGeometry.Frame frame;
        try{frame=portrait?CabinetVideoGeometry.frame(cn.piq.fcarcade.layout.PortraitCabinetGeometry.screen(RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ())),rawAspect,rotation):CabinetVideoGeometry.frame(target.dual(),
                RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ()),rawAspect,rotation,
                cabinet instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual && dual.compactFootprint());}
        catch(IllegalArgumentException invalidAspect){return;} // Malformed provider metadata is not a render-loop crash.
        var camera=event.getCamera().getPosition();var poses=event.getPoseStack();
        poses.pushPose();
        try{
            poses.translate(target.anchor().getX()-camera.x,target.anchor().getY()-camera.y,target.anchor().getZ()-camera.z);
            var buffers=minecraft.renderBuffers().bufferSource();var renderType=cn.piq.fcarcade.client.ScreenRenderMaterial.of(texture);
            var out=buffers.getBuffer(renderType);var pose=poses.last();var normal=frame.image().normal();
            // The main cabinet model's opaque black glass remains visible outside the fitted frame.
            for(var vertex:frame.vertices()){
                var point=vertex.point();
                out.addVertex(pose,(float)point.x(),(float)point.y(),(float)point.z()).setColor(255,255,255,255)
                        .setUv(vertex.u(),vertex.v()).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(pose,(float)normal.x(),(float)normal.y(),(float)normal.z());
            }
            buffers.endBatch(renderType);
        }finally{poses.popPose();}
    }
}
