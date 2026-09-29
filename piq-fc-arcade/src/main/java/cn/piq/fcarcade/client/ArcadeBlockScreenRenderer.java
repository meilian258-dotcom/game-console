package cn.piq.fcarcade.client;

import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.layout.ScreenSurfaceGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class ArcadeBlockScreenRenderer {
    private ArcadeBlockScreenRenderer() {
    }

    static void render(
            RenderLevelStageEvent event,
            ClientArcadeSession session,
            BlockPos pos
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || minecraft.level == null || pos == null) {
            return;
        }
        ArcadeStructure layout = ArcadeStructure.resolve(minecraft.level, pos);
        if (!cn.piq.fcarcade.home.HomeApplianceService.videoAllowed(minecraft.level,layout.anchor())) return;
        if (!session.isVisibleAt(layout.anchor())) return;
        if (minecraft.level.getBlockEntity(layout.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity cabinet
                && !cn.piq.fcarcade.client.cabinet.CabinetClientSettings.rules().visible(event.getCamera().getPosition().distanceToSqr(layout.anchor().getCenter()))) return;
        BlockState state = minecraft.level.getBlockState(layout.anchor());
        if (!(state.getBlock() instanceof FcArcadeBlock)) return;
        FcArcadeBlock arcadeBlock = (FcArcadeBlock) state.getBlock();
        ResourceLocation texture = session.textureAt(layout.anchor());

        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        if (!HomeScreenRenderPose.apply(event, minecraft.level, layout.anchor())) {
            stack.popPose();
            return;
        }

        if (arcadeBlock.displayStyle() == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV) {
            var offset = ScreenSurfaceGeometry.translation(arcadeBlock.displayStyle(), RocketArcadeGeometry.quarterTurns(
                    layout.facing().getStepX(), layout.facing().getStepZ()),
                    cn.piq.fcarcade.home.HomeTvStructure.centered(state));
            stack.translate(offset.x(), offset.y(), offset.z());
        }

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (texture != null) {
            RenderType renderType = ScreenRenderMaterial.of(texture);
            VertexConsumer consumer = buffers.getBuffer(renderType);
            if (minecraft.level.getBlockEntity(layout.anchor())
                    instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv && tv.scanlinesEnabled()) {
                consumer = new CrtScanlineVertexConsumer(consumer, event.getProjectionMatrix(),
                        minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
            }
            drawFace(
                    consumer,
                    stack.last(),
                    layout.facing(),
                    layout.width(),
                    layout.height(),
                    arcadeBlock.displayStyle(),
                    minecraft.level.getBlockEntity(layout.anchor()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv
                            ? HomeApplianceClient.powerAmount(tv) : 1,
                    minecraft.level.getBlockEntity(layout.anchor()) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual
                            && dual.compactFootprint());
            buffers.endBatch(renderType);
        }
        stack.popPose();
    }

    static void drawFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Direction facing,
            int width,
            int height,
            cn.piq.fcarcade.layout.ArcadeDisplayStyle displayStyle
    ) {
        drawFace(consumer, pose, facing, width, height, displayStyle, 1);
    }

    /** TV power envelope is shared by FC, external systems and idle patterns. */
    static void drawFace(VertexConsumer consumer, PoseStack.Pose pose, Direction facing, int width,
                         int height, cn.piq.fcarcade.layout.ArcadeDisplayStyle displayStyle, double powerAmount) {
        drawFace(consumer,pose,facing,width,height,displayStyle,powerAmount,false);
    }
    static void drawFace(VertexConsumer consumer, PoseStack.Pose pose, Direction facing, int width,
                         int height, cn.piq.fcarcade.layout.ArcadeDisplayStyle displayStyle, double powerAmount,
                         boolean compactDual) {
        if (facing.getAxis().isVertical()) return;
        if (powerAmount <= .00001) return;
        var surface = ScreenSurfaceGeometry.frame(displayStyle,
                RocketArcadeGeometry.quarterTurns(facing.getStepX(), facing.getStepZ()),
                width, height, false,
                displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.DUAL_CABINET
                        ? ClientArcadeEvents.dualScreenAspect() : cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE,compactDual);
        var quad = surface.image();
        double amount = Math.max(0, Math.min(1, powerAmount));
        double vertical = powerVertical(displayStyle, amount);
        double middle = (quad.lowerMaxX().y() + quad.upperMaxX().y()) / 2;
        int shade = cn.piq.fcarcade.home.TelevisionPowerTransition.brightness(amount);
        // The same fitted image/UVs are used by aim mapping, never the glass/black bars.
        rocketVertex(consumer, pose, powerPoint(quad.lowerMaxX(), middle, vertical), quad.normal(), 0, 1, shade);
        rocketVertex(consumer, pose, powerPoint(quad.lowerMinX(), middle, vertical), quad.normal(), 1, 1, shade);
        rocketVertex(consumer, pose, powerPoint(quad.upperMinX(), middle, vertical), quad.normal(), 1, 0, shade);
        rocketVertex(consumer, pose, powerPoint(quad.upperMaxX(), middle, vertical), quad.normal(), 0, 0, shade);
    }

    private static RocketArcadeGeometry.Point powerPoint(RocketArcadeGeometry.Point p, double middle, double vertical) {
        return new RocketArcadeGeometry.Point(p.x(), middle + (p.y() - middle) * vertical, p.z());
    }

    static double powerVertical(cn.piq.fcarcade.layout.ArcadeDisplayStyle style, double amount) {
        boolean crt = style == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV
                || style == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_VINTAGE_TV
                || style == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_GRAY_CRT
                || style == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RED_CRT;
        return cn.piq.fcarcade.home.TelevisionPowerTransition.verticalScale(crt, amount);
    }

    private static void rocketVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            RocketArcadeGeometry.Point point,
            RocketArcadeGeometry.Point normal,
            float u,
            float v,
            int shade
    ) {
        vertex(
                consumer,
                pose,
                (float) point.x(),
                (float) point.y(),
                (float) point.z(),
                u,
                v,
                (float) normal.x(),
                (float) normal.y(),
                (float) normal.z(), shade);
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x,
            float y,
            float z,
            float u,
            float v,
            float normalX,
            float normalY,
            float normalZ,
            int shade
    ) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(shade, shade, shade, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, normalX, normalY, normalZ);
    }
}
