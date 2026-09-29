package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.layout.DualCabinetGeometry;
import cn.piq.fcarcade.layout.DualCabinetControls;
import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.cabinet.CabinetTarget;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.world.DualCabinetBlockEntity;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Exactly one full cabinet draw from its anchor; proxies are invisible. */
public final class DualCabinetRenderer implements BlockEntityRenderer<DualCabinetBlockEntity> {
    private static ModelResourceLocation partModel(String name) { return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "block/user_dual/"+name)); }
    private static final ModelResourceLocation BODY = partModel("body");
    private static final List<ModelResourceLocation> PART_MODELS = DualCabinetControls.PARTS.stream()
            .map(part->partModel(part.name())).toList();
    private static final Map<ModelResourceLocation,Cached> CACHE = new HashMap<>();
    public DualCabinetRenderer(BlockEntityRendererProvider.Context ignored) {}
    public static void register(IEventBus bus) {
        bus.addListener(DualCabinetRenderer::renderers);
        bus.addListener(DualCabinetRenderer::models);
        bus.addListener(DualCabinetRenderer::items);
    }
    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(ModBlockEntities.DUAL_CABINET.get(), DualCabinetRenderer::new);
    }
    private static void models(ModelEvent.RegisterAdditional e) { e.register(BODY); PART_MODELS.forEach(e::register); }
    private static void items(RegisterClientExtensionsEvent e) {
        e.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new ItemRenderer();
                return renderer;
            }
        }, ModItems.DUAL_CABINET.get());
    }
    @Override public boolean shouldRenderOffScreen(DualCabinetBlockEntity machine) { return true; }
    @Override public AABB getRenderBoundingBox(DualCabinetBlockEntity machine) {
        var facing = machine.getBlockState().getValue(FcArcadeBlock.FACING);
        var b = DualCabinetGeometry.bounds(RocketArcadeGeometry.quarterTurns(facing.getStepX(), facing.getStepZ()),machine.compactFootprint());
        return CabinetDataCableRenderer.bounds(machine,new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).inflate(.06).move(machine.getBlockPos()));
    }
    @Override public void render(DualCabinetBlockEntity machine, float partial, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
        CabinetDataCableRenderer.render(machine,poses,buffers,light,overlay);
        CabinetPowerRenderer.render(machine,poses,buffers);
        var facing = machine.getBlockState().getValue(FcArcadeBlock.FACING);
        int turns = RocketArcadeGeometry.quarterTurns(facing.getStepX(), facing.getStepZ());
        // Existing hashes identify legacy UV layouts, not this user's new atlas.
        // Preserve saved skinHash and the single-cabinet skin path, but do not
        // silently project an untagged old PNG onto this incompatible model.
        // This revision intentionally renders the supplied default skin only.
        ResourceLocation skin = null;
        boolean nes = CabinetBackends.NES.equals(machine.cabinetBackend());
        var inputLayout = DualCabinetControls.layoutForBackend(machine.cabinetBackend().toString());
        int[] inputs = nes ? ClientArcadeEvents.cabinetVisualInputs(machine.getBlockPos())
                : CabinetClientBackends.visualInputs(new CabinetTarget(machine.getLevel().dimension().location(),
                machine.getBlockPos(),machine.cabinetId(),true));
        poses.pushPose();
        try {
            poses.translate(.5,0,.5);
            poses.mulPose(Axis.YP.rotationDegrees(-90F * turns));
            poses.translate(-.5,0,-.5);
            poses.scale(DualCabinetGeometry.MODEL_SCALE,DualCabinetGeometry.MODEL_SCALE,DualCabinetGeometry.MODEL_SCALE);
            poses.translate(DualCabinetGeometry.MODEL_X_OFFSET,DualCabinetGeometry.MODEL_Y_OFFSET,
                    DualCabinetGeometry.modelZOffset(machine.compactFootprint()));
            draw(poses,buffers,light,overlay,skin,inputs,inputLayout);
        } finally { poses.popPose(); }
    }
    private static void draw(PoseStack poses, MultiBufferSource buffers, int light, int overlay,
                             ResourceLocation skin,int[] inputs,DualCabinetControls.InputLayout inputLayout) {
        drawModel(BODY,poses,buffers,light,overlay,skin);
        for(int i=0;i<DualCabinetControls.PARTS.size();i++) {
            var part=DualCabinetControls.PARTS.get(i);
            var motion=DualCabinetControls.motion(part,inputs[part.player()],inputLayout);
            poses.pushPose();
            try {
                poses.translate(0,motion.pressY()/16,0);
                if(part.joystick()) {
                    poses.translate(part.x()/16,part.y()/16,part.z()/16);
                    poses.mulPose(Axis.ZP.rotationDegrees((float)motion.tiltZ()));
                    poses.mulPose(Axis.XP.rotationDegrees((float)motion.tiltX()));
                    poses.translate(-part.x()/16,-part.y()/16,-part.z()/16);
                }
                drawModel(PART_MODELS.get(i),poses,buffers,light,overlay,skin);
            } finally { poses.popPose(); }
        }
    }
    private static void drawModel(ModelResourceLocation id,PoseStack poses, MultiBufferSource buffers,
                                  int light,int overlay,ResourceLocation skin) {
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(id);
        Cached cached = CACHE.get(id);
        if (cached == null || cached.model() != model) {
            var random = RandomSource.create(0x504951L);
            var quads = new ArrayList<>(model.getQuads(null,null,random));
            for (Direction side : Direction.values()) {
                random.setSeed(0x504951L); quads.addAll(model.getQuads(null,side,random));
            }
            var prepared = new ArrayList<Face>(quads.size());
            for (var q : quads) prepared.add(new Face(q,isScreen(q)));
            cached = new Cached(model,List.copyOf(prepared));
            CACHE.put(id,cached);
        }
        VertexConsumer atlas = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        SkinUv custom = skin == null ? null : new SkinUv(buffers.getBuffer(RenderType.entityCutoutNoCull(skin)));
        for (Face face : cached.faces()) {
            VertexConsumer target = atlas;
            if (custom != null && !face.screen()) { custom.sprite = face.quad().getSprite(); target = custom; }
            // The static glass always remains the default black face, including when a skin is loaded.
            target.putBulkData(poses.last(),face.quad(),1,1,1,1,light,overlay);
        }
    }
    private static boolean isScreen(BakedQuad quad) {
        var q = DualCabinetGeometry.screen(0);
        var corners = new RocketArcadeGeometry.Point[]{q.lowerMinX(),q.lowerMaxX(),q.upperMaxX(),q.upperMinX()};
        int matched = 0;
        int[] data = quad.getVertices();
        for (int v=0;v<4;v++) {
            int p = v * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
            double x = (Float.intBitsToFloat(data[p]) + DualCabinetGeometry.MODEL_X_OFFSET) * DualCabinetGeometry.MODEL_SCALE;
            double y = (Float.intBitsToFloat(data[p+1]) + DualCabinetGeometry.MODEL_Y_OFFSET) * DualCabinetGeometry.MODEL_SCALE;
            double z = (Float.intBitsToFloat(data[p+2]) + DualCabinetGeometry.MODEL_Z_OFFSET) * DualCabinetGeometry.MODEL_SCALE;
            for (int i=0;i<4;i++) {
                var c = corners[i];
                if ((matched & (1<<i)) == 0 && Math.abs(x-c.x()) < .00002
                        && Math.abs(y-(c.y()-q.normal().y()*DualCabinetGeometry.SCREEN_OFFSET)) < .00002
                        && Math.abs(z-(c.z()-q.normal().z()*DualCabinetGeometry.SCREEN_OFFSET)) < .00002) matched |= 1<<i;
            }
        }
        return matched == 15;
    }
    private record Face(BakedQuad quad, boolean screen) {}
    private record Cached(BakedModel model, List<Face> faces) {}
    private static final class SkinUv implements VertexConsumer {
        private final VertexConsumer target;
        private TextureAtlasSprite sprite;
        private SkinUv(VertexConsumer target) { this.target = target; }
        @Override public VertexConsumer addVertex(float x,float y,float z) { target.addVertex(x,y,z); return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { target.setColor(r,g,b,a); return this; }
        @Override public VertexConsumer setUv(float u,float v) { target.setUv(sprite.getUOffset(u),sprite.getVOffset(v)); return this; }
        @Override public VertexConsumer setUv1(int u,int v) { target.setUv1(u,v); return this; }
        @Override public VertexConsumer setUv2(int u,int v) { target.setUv2(u,v); return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { target.setNormal(x,y,z); return this; }
    }
    private static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        private ItemRenderer() { super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels()); }
        @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                                          MultiBufferSource buffers, int light, int overlay) {
            poses.pushPose();
            try {
                // Centre exact new body; item presentation never borrows an active player's input.
                poses.translate(.5,.5,.5); poses.scale(.40F,.40F,.40F); poses.translate(-1,-1,-.5738756313208677);
                poses.translate(DualCabinetGeometry.MODEL_X_OFFSET,DualCabinetGeometry.MODEL_Y_OFFSET,0);
                draw(poses,buffers,light,overlay,null,new int[]{0,0},DualCabinetControls.InputLayout.ARCADE);
            } finally { poses.popPose(); }
        }
    }
}
