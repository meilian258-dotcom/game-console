package cn.piq.pvz.client;

import cn.piq.fcarcade.client.HomeAvCableMesh;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.client.HomeHardwareRenderer;
import cn.piq.fcarcade.client.UserTvCableMesh;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.pvz.registry.PvzRegistries;
import cn.piq.pvz.world.PvzBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Uses the base mod's routing/plug mesh and wire radius; the baked case is not drawn twice. */
public final class PvzCableRenderer implements BlockEntityRenderer<PvzBlockEntity> {
    private static final ResourceLocation SOLID = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "textures/block/home_retro_tv_white.png");
    private record Key(ArcadeDisplayStyle style, int boxTurns, int tvTurns, int dx, int dy, int dz) {}
    private record Cached(Key key, List<HomeAvCableMesh.Quad> quads) {}
    private final Map<PvzBlockEntity, Cached> cache = new WeakHashMap<>();
    public PvzCableRenderer(BlockEntityRendererProvider.Context context) {}
    /** Call from a client-only mod setup owner. Never register this from common code. */
    public static void register(IEventBus bus) { bus.addListener(PvzCableRenderer::registerRenderers); }
    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PvzRegistries.BOX_ENTITY.get(), PvzCableRenderer::new);
    }
    private static HomeTvBlockEntity connectedTv(PvzBlockEntity box) {
        var level = box.getLevel(); var pos = box.televisionPos();
        if (level == null || box.isRemoved() || pos == null || !level.hasChunkAt(pos)
                || !level.hasChunkAt(box.getBlockPos()) || level.getBlockEntity(box.getBlockPos()) != box
                || !(level.getBlockEntity(pos) instanceof HomeTvBlockEntity tv)) return null;
        return HomeHardware.connected(level, box, tv) ? tv : null;
    }
    private static int turns(BlockState state) {
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        return switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }
    @Override public void render(PvzBlockEntity box, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        var tv = connectedTv(box);
        if (tv == null || !(tv.getBlockState().getBlock() instanceof FcArcadeBlock tvBlock) || !UserTvLayout.supports(tvBlock.displayStyle())) {
            cache.remove(box); return;
        }
        var d = tv.getBlockPos().subtract(box.getBlockPos());
        var key = new Key(tvBlock.displayStyle(), turns(box.getBlockState()), turns(tv.getBlockState()), d.getX(), d.getY(), d.getZ());
        var prior = cache.get(box);
        if (prior == null || !prior.key.equals(key)) { prior = new Cached(key, build(key)); cache.put(box, prior); }
        if (prior.quads.isEmpty()) return; // No invented straight line if shared safe-route validation rejects it.
        var output = buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));
        for (var q : prior.quads) {
            vertex(output, poses.last(), q.a(), q.normal(), q.color(), light, overlay);
            vertex(output, poses.last(), q.b(), q.normal(), q.color(), light, overlay);
            vertex(output, poses.last(), q.c(), q.normal(), q.color(), light, overlay);
            vertex(output, poses.last(), q.d(), q.normal(), q.color(), light, overlay);
        }
    }
    private static List<HomeAvCableMesh.Quad> build(Key key) {
        var socket = HomeHardwareRenderLayout.rotate(new Point(.5, 1.5/16, 13.2/16), key.boxTurns);
        var a = HomeHardwareRenderLayout.rotate(new Point(2D/16, 0, 2.6/16), key.boxTurns);
        var b = HomeHardwareRenderLayout.rotate(new Point(14D/16, 3D/16, 13.2/16), key.boxTurns);
        var bounds = new UserTvCableMesh.Bounds(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),3D/16,Math.max(a.z(),b.z()));
        return UserTvCableMesh.buildMultiOut(socket, bounds, key.boxTurns, .6, key.style, key.tvTurns, key.dx, key.dy, key.dz);
    }
    private static void vertex(VertexConsumer output, PoseStack.Pose pose, Point p, Point normal, int rgb, int light, int overlay) {
        output.addVertex(pose,(float)p.x(),(float)p.y(),(float)p.z()).setColor((rgb>>16)&255,(rgb>>8)&255,rgb&255,255)
                .setUv(.5f,.5f).setOverlay(overlay).setLight(light).setNormal(pose,(float)normal.x(),(float)normal.y(),(float)normal.z());
    }
    @Override public boolean shouldRenderOffScreen(PvzBlockEntity box) { return true; }
    @Override public AABB getRenderBoundingBox(PvzBlockEntity box) {
        AABB own = new AABB(box.getBlockPos());
        var tv = connectedTv(box);
        return tv == null ? own : own.minmax(HomeHardwareRenderer.tvRenderBounds(tv.getBlockPos(),tv.getBlockState())).inflate(.45);
    }
}
