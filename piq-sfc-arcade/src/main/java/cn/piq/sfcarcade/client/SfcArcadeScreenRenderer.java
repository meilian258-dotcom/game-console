// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.world.SfcArcadeBlock;
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

final class SfcArcadeScreenRenderer {
    private static final float EPSILON = 0.002F;
    private static final float MAX_WIDTH = 0.80F;
    private static final float MAX_HEIGHT = 0.75F;

    private SfcArcadeScreenRenderer() {
    }

    static void render(RenderLevelStageEvent event, LocalSfcSession session) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = session.blockPos();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || minecraft.level == null || pos == null
                || !session.matchesLevel(minecraft.level)) {
            return;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof SfcArcadeBlock)) return;
        // Client ticks run at only 20 Hz. Upload at the render stage so a
        // 60 Hz SFC stream remains smooth on clients rendering at 60+ FPS.
        session.uploadPendingFrame(minecraft);
        ResourceLocation texture = session.textureLocation();
        if (texture == null) return;

        var camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        stack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);

        RenderType renderType = RenderType.entityCutoutNoCull(texture);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(renderType);
        drawFace(consumer, stack.last(), state.getValue(SfcArcadeBlock.FACING), session.displayAspect());
        buffers.endBatch(renderType);
        stack.popPose();
    }

    private static void drawFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Direction facing,
            float aspect
    ) {
        float width = MAX_WIDTH;
        float height = width / Math.max(0.1F, aspect);
        if (height > MAX_HEIGHT) {
            height = MAX_HEIGHT;
            width = height * aspect;
        }
        float min = (1.0F - width) * 0.5F;
        float max = min + width;
        float bottom = (1.0F - height) * 0.5F;
        float top = bottom + height;
        float near = -EPSILON;
        float far = 1.0F + EPSILON;
        switch (facing) {
            case NORTH -> {
                vertex(consumer, pose, max, bottom, near, 0, 1, 0, 0, -1);
                vertex(consumer, pose, min, bottom, near, 1, 1, 0, 0, -1);
                vertex(consumer, pose, min, top, near, 1, 0, 0, 0, -1);
                vertex(consumer, pose, max, top, near, 0, 0, 0, 0, -1);
            }
            case SOUTH -> {
                vertex(consumer, pose, min, bottom, far, 0, 1, 0, 0, 1);
                vertex(consumer, pose, max, bottom, far, 1, 1, 0, 0, 1);
                vertex(consumer, pose, max, top, far, 1, 0, 0, 0, 1);
                vertex(consumer, pose, min, top, far, 0, 0, 0, 0, 1);
            }
            case WEST -> {
                vertex(consumer, pose, near, bottom, min, 0, 1, -1, 0, 0);
                vertex(consumer, pose, near, bottom, max, 1, 1, -1, 0, 0);
                vertex(consumer, pose, near, top, max, 1, 0, -1, 0, 0);
                vertex(consumer, pose, near, top, min, 0, 0, -1, 0, 0);
            }
            case EAST -> {
                vertex(consumer, pose, far, bottom, max, 0, 1, 1, 0, 0);
                vertex(consumer, pose, far, bottom, min, 1, 1, 1, 0, 0);
                vertex(consumer, pose, far, top, min, 1, 0, 1, 0, 0);
                vertex(consumer, pose, far, top, max, 0, 0, 1, 0, 0);
            }
            default -> {
            }
        }
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x,
            float y,
            float z,
            float u,
            float v,
            float nx,
            float ny,
            float nz
    ) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
