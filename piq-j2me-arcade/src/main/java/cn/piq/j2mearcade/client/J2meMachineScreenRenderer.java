package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.world.J2meArcadeBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class J2meMachineScreenRenderer {
    private static final float MIN_X = 4.0F / 16.0F;
    private static final float MAX_X = 12.0F / 16.0F;
    private static final float MIN_Y = 4.0F / 16.0F;
    private static final float MAX_Y = 14.0F / 16.0F;
    private static final float FRONT = 1.85F / 16.0F;

    private J2meMachineScreenRenderer() {
    }

    static void render(RenderLevelStageEvent event, ClientJ2meMachineRuntime runtime) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = runtime.blockPos();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || minecraft.level == null || pos == null || !runtime.activeAt(pos)) {
            return;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof J2meArcadeBlock)) {
            return;
        }
        Direction facing = state.getValue(J2meArcadeBlock.FACING);
        var camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        stack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        RenderType renderType = RenderType.entityCutoutNoCull(runtime.textureId());
        draw(buffers.getBuffer(renderType), stack.last(), facing);
        buffers.endBatch(renderType);
        stack.popPose();
    }

    private static void draw(VertexConsumer consumer, PoseStack.Pose pose, Direction facing) {
        switch (facing) {
            case NORTH -> quad(consumer, pose,
                    MAX_X, MIN_Y, FRONT, MIN_X, MIN_Y, FRONT,
                    MIN_X, MAX_Y, FRONT, MAX_X, MAX_Y, FRONT, 0, 0, -1);
            case SOUTH -> quad(consumer, pose,
                    MIN_X, MIN_Y, 1 - FRONT, MAX_X, MIN_Y, 1 - FRONT,
                    MAX_X, MAX_Y, 1 - FRONT, MIN_X, MAX_Y, 1 - FRONT, 0, 0, 1);
            case WEST -> quad(consumer, pose,
                    FRONT, MIN_Y, MIN_X, FRONT, MIN_Y, MAX_X,
                    FRONT, MAX_Y, MAX_X, FRONT, MAX_Y, MIN_X, -1, 0, 0);
            case EAST -> quad(consumer, pose,
                    1 - FRONT, MIN_Y, MAX_X, 1 - FRONT, MIN_Y, MIN_X,
                    1 - FRONT, MAX_Y, MIN_X, 1 - FRONT, MAX_Y, MAX_X, 1, 0, 0);
            default -> {
            }
        }
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4,
            float nx, float ny, float nz) {
        vertex(consumer, pose, x1, y1, z1, 0, 1, nx, ny, nz);
        vertex(consumer, pose, x2, y2, z2, 1, 1, nx, ny, nz);
        vertex(consumer, pose, x3, y3, z3, 1, 0, nx, ny, nz);
        vertex(consumer, pose, x4, y4, z4, 0, 0, nx, ny, nz);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose,
            float x, float y, float z, float u, float v,
            float nx, float ny, float nz) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
