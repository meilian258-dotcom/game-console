package cn.piq.fcarcade.client;

import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import net.minecraft.world.phys.AABB;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import cn.piq.fcarcade.layout.DualCabinetControls;
import cn.piq.fcarcade.layout.DualCabinetControls.Part;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import java.util.*;

final class LegacyArcadeSkinRenderer
        implements BlockEntityRenderer<LegacyFcArcadeBlockEntity> {
    private static final List<Part> PARTS=List.of(
            new Part("button_1",0,0,7.35,16.21,1.52),new Part("button_2",0,1,6.23,16.21,1.64),
            new Part("button_3",0,2,5.11,16.21,1.52),new Part("button_4",0,3,7.35,16.21,2.75),
            new Part("button_5",0,4,6.23,16.21,2.87),new Part("button_6",0,5,5.11,16.21,2.75),
            new Part("start",0,6,8.69,16.22,1.51),new Part("joystick",0,-1,10.6,16.34,2.95));
    private record Cached(BakedModel model,List<BakedQuad> quads){}
    private static final Map<String,Cached> CACHE=new HashMap<>();
    private static ModelResourceLocation partModel(String name){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","block/legacy_animated/"+name));}
    static void register(IEventBus bus){bus.addListener((ModelEvent.RegisterAdditional e)->PARTS.forEach(p->e.register(partModel(p.name()))));}
    LegacyArcadeSkinRenderer(BlockEntityRendererProvider.Context ignored) {
    }
    @Override public boolean shouldRenderOffScreen(LegacyFcArcadeBlockEntity e){return true;}

    @Override
    public AABB getRenderBoundingBox(LegacyFcArcadeBlockEntity machine) {
        var pos = machine.getBlockPos();
        return CabinetDataCableRenderer.bounds(machine,new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + RocketArcadeGeometry.MODEL_TOP, pos.getZ() + 1));
    }

    @Override
    public void render(
            LegacyFcArcadeBlockEntity machine,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay
    ) {
        CabinetDataCableRenderer.render(machine,poseStack,buffers,packedLight,packedOverlay);
        CabinetPowerRenderer.render(machine,poseStack,buffers);
        String sha256 = machine.skinHash();
        ResourceLocation texture = sha256.isEmpty()?null:ClientSkinManager.textureFor(sha256);
        renderControls(machine,poseStack,buffers,packedLight,packedOverlay,texture);
        // Legacy 512 UV files deliberately resolve to null: the baked rocket skin stays visible.
        // Do not clear skinHash or rewrite the block entity's preserved legacy NBT here.
        if (texture == null) return;

        var model = Minecraft.getInstance()
                .getBlockRenderer()
                .getBlockModel(machine.getBlockState());
        VertexConsumer delegate = buffers.getBuffer(
                RenderType.entityCutoutNoCullZOffset(texture));
        RandomSource random = RandomSource.create(0x504951L);
        renderQuads(
                model.getQuads(machine.getBlockState(), null, random),
                delegate,
                poseStack.last(),
                packedLight,
                packedOverlay);
        for (Direction side : Direction.values()) {
            random.setSeed(0x504951L);
            renderQuads(
                    model.getQuads(machine.getBlockState(), side, random),
                    delegate,
                    poseStack.last(),
                    packedLight,
                    packedOverlay);
        }
    }

    private static void renderControls(LegacyFcArcadeBlockEntity machine,PoseStack poses,MultiBufferSource buffers,int light,int overlay,ResourceLocation texture){
        var facing=machine.getBlockState().getValue(FcArcadeBlock.FACING);
        int turns=RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ());
        int[] input=machine.cabinetBackend().equals(CabinetBackends.NES)?ClientArcadeEvents.cabinetVisualInputs(machine.getBlockPos())
                :CabinetClientBackends.visualInputs(new CabinetTarget(machine.getLevel().dimension().location(),machine.getBlockPos(),machine.cabinetId(),false));
        var layout=DualCabinetControls.layoutForBackend(machine.cabinetBackend().toString());
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(texture==null?TextureAtlas.LOCATION_BLOCKS:texture));
        poses.pushPose();try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*turns));poses.translate(-.5,0,-.5);
            for(var part:PARTS){
                var model=Minecraft.getInstance().getModelManager().getModel(partModel(part.name()));var cached=CACHE.get(part.name());
                if(cached==null||cached.model()!=model){
                    var random=RandomSource.create(42);var quads=new ArrayList<>(model.getQuads(null,null,random));
                    for(var d:Direction.values()){random.setSeed(42);quads.addAll(model.getQuads(null,d,random));}
                    cached=new Cached(model,List.copyOf(quads));CACHE.put(part.name(),cached);
                }
                var motion=DualCabinetControls.motion(part,input.length==0?0:input[0],layout);
                poses.pushPose();try{
                    poses.translate(0,motion.pressY()/16,0);
                    if(part.joystick()){
                        poses.translate(part.x()/16,part.y()/16,part.z()/16);
                        poses.mulPose(Axis.ZP.rotationDegrees((float)motion.tiltZ()));poses.mulPose(Axis.XP.rotationDegrees((float)motion.tiltX()));
                        poses.translate(-part.x()/16,-part.y()/16,-part.z()/16);
                    }
                    if(texture!=null)renderQuads(cached.quads(),out,poses.last(),light,overlay);
                    else for(var quad:cached.quads())out.putBulkData(poses.last(),quad,1,1,1,1,light,overlay);
                }finally{poses.popPose();}
            }
        }finally{poses.popPose();}
    }

    private static void renderQuads(
            java.util.List<BakedQuad> quads,
            VertexConsumer delegate,
            PoseStack.Pose pose,
            int packedLight,
            int packedOverlay
    ) {
        for (BakedQuad quad : quads) {
            // VIEW_OFFSET_Z_LAYERING pulls the overlay towards the camera by a distance-dependent
            // amount. Repainting the static screen would therefore occlude the live screen at range.
            // Keep that one quad in the baked/default layer only; cabinet frames still need ZOffset.
            if (RocketSkinQuadFilter.isStaticScreenFace(
                    quad.getVertices(), IQuadTransformer.STRIDE, IQuadTransformer.POSITION)) continue;
            VertexConsumer remapped = new AtlasUvRemapper(
                    delegate,
                    quad.getSprite());
            remapped.putBulkData(
                    pose,
                    quad,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F,
                    packedLight,
                    packedOverlay);
        }
    }

    private static final class AtlasUvRemapper implements VertexConsumer {
        private final VertexConsumer delegate;
        private final TextureAtlasSprite sprite;

        private AtlasUvRemapper(
                VertexConsumer delegate,
                TextureAtlasSprite sprite
        ) {
            this.delegate = delegate;
            this.sprite = sprite;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(sprite.getUOffset(u), sprite.getVOffset(v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
