package cn.piq.fcarcade.client;

import cn.piq.fcarcade.cabinet.WatchDescriptor;
import cn.piq.fcarcade.client.watch.WatchClient;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Receive-only FC home display: no ROM, core, seat or input authority. */
final class FcHomeWatchDisplay implements WatchClient.DisplayAdapter {
    static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "home_player");
    static void register() {
        WatchClient.registerDisplay(PROVIDER, new FcHomeWatchDisplay());
        WatchClient.registerHost(PROVIDER, ClientArcadeEvents::playerMediaDemand);
    }
    @Override public boolean valid(WatchDescriptor d) {
        var level = Minecraft.getInstance().level;
        if (level == null || !PROVIDER.equals(d.provider()) || !level.dimension().location().equals(d.dimension())
                || d.link() == null || d.screens().size() != 1) return false;
        var screen = d.screens().getFirst();
        if (!level.hasChunkAt(d.origin().pos()) || !level.hasChunkAt(screen.pos())) return false;
        return level.getBlockEntity(d.origin().pos()) instanceof HomeConsoleBlockEntity console
                && level.getBlockEntity(screen.pos()) instanceof HomeTvBlockEntity tv
                && d.origin().identity().equals(console.hardwareId()) && screen.identity().equals(tv.hardwareId())
                && d.link().equals(console.linkId()) && d.link().equals(tv.linkId())
                && screen.pos().equals(console.tvPos()) && d.origin().pos().equals(tv.consolePos())
                && HomeTvStructure.complete(level, screen.pos())
                && ArcadeStructure.resolve(level, screen.pos()).anchor().equals(screen.pos());
    }
    @Override public boolean isParticipant(WatchDescriptor d) { return ClientArcadeEvents.hasHomeParticipant(); }
    @Override public float volume(WatchDescriptor d) {
        if (!valid(d)) return 0;
        var mc = Minecraft.getInstance(); if (mc.player == null) return 0;
        return HomeApplianceService.audioGain(mc.level, d.screens().getFirst().pos())
                * (float)Math.max(0, 1 - Math.sqrt(mc.player.distanceToSqr(d.screens().getFirst().pos().getCenter())) / 16);
    }
    @Override public void render(RenderLevelStageEvent event, WatchDescriptor d, ResourceLocation texture, float aspect, int rotation) {
        if (rotation != 0 || !valid(d) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance(); var pos = d.screens().getFirst().pos();
        if (!HomeApplianceService.videoAllowed(mc.level,pos)) return;
        var tv = (HomeTvBlockEntity)mc.level.getBlockEntity(pos);
        if (!(tv.getBlockState().getBlock() instanceof FcArcadeBlock block)) return;
        var layout = ArcadeStructure.resolve(mc.level,pos);
        var stack = event.getPoseStack();
        stack.pushPose();
        try {
            if (!HomeScreenRenderPose.apply(event,mc.level,pos)) return;
            if (block.displayStyle() == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV) {
                var offset = cn.piq.fcarcade.layout.ScreenSurfaceGeometry.translation(block.displayStyle(),
                        RocketArcadeGeometry.quarterTurns(layout.facing().getStepX(), layout.facing().getStepZ()),
                        HomeTvStructure.centered(tv.getBlockState()));
                stack.translate(offset.x(), offset.y(), offset.z());
            }
            var buffers = mc.renderBuffers().bufferSource(); var type = ScreenRenderMaterial.of(texture);
            var consumer = buffers.getBuffer(type);
            if (tv.scanlinesEnabled()) consumer = new CrtScanlineVertexConsumer(consumer,event.getProjectionMatrix(),
                    mc.getWindow().getWidth(),mc.getWindow().getHeight());
            ArcadeBlockScreenRenderer.drawFace(consumer,stack.last(),layout.facing(),layout.width(),layout.height(),
                    block.displayStyle(),HomeApplianceClient.powerAmount(tv));
            buffers.endBatch(type);
        } finally { stack.popPose(); }
    }
}
