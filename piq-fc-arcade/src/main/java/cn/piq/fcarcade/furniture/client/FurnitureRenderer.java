package cn.piq.fcarcade.furniture.client;

import cn.piq.fcarcade.furniture.FoldingStoolBlock;
import cn.piq.fcarcade.furniture.FurnitureBlock;
import cn.piq.fcarcade.furniture.FurnitureBlockEntity;
import cn.piq.fcarcade.furniture.WoodSpecies;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** One independently textured mesh at the anchor; the bench proxy block never draws another copy. */
public final class FurnitureRenderer implements BlockEntityRenderer<FurnitureBlockEntity> {
    private static volatile FurnitureResources.Snapshot resources = FurnitureResources.Snapshot.empty();
    public FurnitureRenderer(BlockEntityRendererProvider.Context ignored) {}
    static void replace(FurnitureResources.Snapshot next) { resources = next; }

    @Override public boolean shouldRenderOffScreen(FurnitureBlockEntity entity) {
        // The anchor's chunk can be out of view while the second block is visible.
        // NeoForge still tests getRenderBoundingBox for global BEs; the dispatcher limits distance to 64.
        var state = entity.getBlockState();
        return state.getBlock() instanceof FurnitureBlock block && block.isBench() && FurnitureBlock.isAnchor(state);
    }

    @Override public AABB getRenderBoundingBox(FurnitureBlockEntity entity) {
        // Exact enclosing square is rotation-safe for the anchor plus its one-block extension.
        // It is finite (unlike global/offscreen rendering) and avoids vanishing at chunk/frustum edges.
        return new AABB(-1, 0, -1, 2, 1, 2).move(entity.getBlockPos());
    }

    @Override public void render(FurnitureBlockEntity entity, float partial, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
        var state = entity.getBlockState();
        if (!(state.getBlock() instanceof FurnitureBlock block) || !block.isAnchor(state)) return;
        var shape = block.isBench() ? FurnitureResources.Shape.BENCH
                : state.getValue(FoldingStoolBlock.FOLDED) ? FurnitureResources.Shape.FOLDED : FurnitureResources.Shape.OPEN;
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.mulPose(Axis.YP.rotationDegrees(rotation(state.getValue(BlockStateProperties.HORIZONTAL_FACING))));
            poses.translate(-.5, 0, -.5);
            draw(shape, block.wood(), poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }

    static float rotation(Direction facing) {
        return switch (facing) { case EAST -> -90; case SOUTH -> 180; case WEST -> 90; default -> 0; };
    }

    static void applyItemFit(boolean bench, boolean folded, PoseStack poses) {
        poses.translate(.5, .5, .5);
        float scale = bench ? .56F : 1.35F;
        poses.scale(scale, scale, scale);
        poses.translate(bench ? -1 : -.5, bench ? -.25 : folded ? -.294766 : -.192231, -.5);
    }

    private static void draw(FurnitureResources.Shape shape, WoodSpecies wood, PoseStack poses,
                             MultiBufferSource buffers, int light, int overlay) {
        var current = resources;
        var mesh = current.meshes().get(shape);
        var materials = current.materials().get(wood);
        if (mesh == null || materials == null) return;
        var atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        // Culling is intentional. The conversion repairs overlaps/winding; NoCull is not a geometry fix.
        var target = buffers.getBuffer(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        var pose = poses.last();
        for (var part : mesh) {
            var sprite = atlas.apply(materials.sprite(part.material()));
            var data = part.vertices();
            for (int triangle = 0; triangle < data.length; triangle += 24) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    int at = triangle + Math.min(vertex, 2) * 8;
                    // Atlas coordinates follow the live stitched image, resource-pack resolution and animation.
                    target.addVertex(pose, data[at], data[at+1], data[at+2]).setColor(255,255,255,255)
                            .setUv(sprite.getU(data[at+3]), sprite.getV(data[at+4]))
                            .setOverlay(overlay).setLight(light).setNormal(pose, data[at+5], data[at+6], data[at+7]);
                }
            }
        }
    }

    public static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        public ItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) { super(dispatcher, models); }
        @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                                          MultiBufferSource buffers, int light, int overlay) {
            if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof FurnitureBlock block)) return;
            boolean bench = block.isBench();
            var itemState = stack.get(DataComponents.BLOCK_STATE);
            boolean folded = !bench && itemState != null && Boolean.TRUE.equals(itemState.get(FoldingStoolBlock.FOLDED));
            var shape = bench ? FurnitureResources.Shape.BENCH
                    : folded ? FurnitureResources.Shape.FOLDED : FurnitureResources.Shape.OPEN;
            poses.pushPose();
            try {
                // Item-only presentation fitting; the placed model remains exactly the supplied scale.
                applyItemFit(bench, folded, poses);
                draw(shape, block.wood(), poses, buffers, light, overlay);
            } finally { poses.popPose(); }
        }
    }
}
