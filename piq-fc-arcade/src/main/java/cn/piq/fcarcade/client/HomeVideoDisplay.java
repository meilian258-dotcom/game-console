package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.UUID;

/** Narrow client-only display API. It cannot create links or start a NES session. */
public final class HomeVideoDisplay {
    private HomeVideoDisplay() {}

    public static boolean render(RenderLevelStageEvent event, ResourceLocation systemId,
                                 BlockPos consolePos, UUID consoleId, BlockPos televisionPos,
                                 UUID televisionId, UUID linkId, ResourceLocation texture,
                                 double pictureAspect) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || mc.level == null
                || texture == null || systemId == null || consolePos == null || consoleId == null
                || televisionPos == null || televisionId == null || linkId == null
                || !Double.isFinite(pictureAspect) || pictureAspect < 0.25 || pictureAspect > 4
                || !mc.level.hasChunkAt(consolePos) || !mc.level.hasChunkAt(televisionPos)) return false;
        if (!cn.piq.fcarcade.home.HomeApplianceService.videoAllowed(mc.level,televisionPos)) return false;
        if (!(mc.level.getBlockEntity(consolePos) instanceof ExternalHomeConsoleBlockEntity console)
                || !(mc.level.getBlockEntity(televisionPos) instanceof HomeTvBlockEntity tv)
                || !systemId.equals(console.systemId()) || !consoleId.equals(console.hardwareId())
                || !televisionId.equals(tv.hardwareId()) || !linkId.equals(console.linkId())
                || !linkId.equals(tv.linkId()) || !televisionPos.equals(console.televisionPos())
                || !consolePos.equals(tv.consolePos()) || !HomeTvStructure.complete(mc.level, televisionPos)) return false;
        var layout = ArcadeStructure.resolve(mc.level, televisionPos);
        if (!layout.anchor().equals(televisionPos)) return false;
        var state = tv.getBlockState();
        if (!(state.getBlock() instanceof FcArcadeBlock block)) return false;
        var camera = event.getCamera().getPosition();
        var stack = event.getPoseStack();
        stack.pushPose();
        try {
            stack.translate(televisionPos.getX()-camera.x, televisionPos.getY()-camera.y, televisionPos.getZ()-camera.z);
            if (block.displayStyle() == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV) {
                var offset = cn.piq.fcarcade.layout.ScreenSurfaceGeometry.translation(block.displayStyle(), RocketArcadeGeometry.quarterTurns(
                        layout.facing().getStepX(), layout.facing().getStepZ()), HomeTvStructure.centered(state));
                stack.translate(offset.x(), offset.y(), offset.z());
            }
            var buffers = mc.renderBuffers().bufferSource();
            var type = ScreenRenderMaterial.of(texture);
            double power = HomeApplianceClient.powerAmount(tv);
            // fitPicture runs after the geometric envelope; compensate so it does not squeeze
            // the width a second time while the CRT opens from a horizontal line.
            double displayAspect = pictureAspect / ArcadeBlockScreenRenderer.powerVertical(block.displayStyle(), power);
            var consumer = new CrtScanlineVertexConsumer(buffers.getBuffer(type), event.getProjectionMatrix(),
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), displayAspect, tv.scanlinesEnabled());
            ArcadeBlockScreenRenderer.drawFace(consumer, stack.last(), layout.facing(), layout.width(), layout.height(),
                    block.displayStyle(), power);
            buffers.endBatch(type);
            return true;
        } finally { stack.popPose(); }
    }
}
