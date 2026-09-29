// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.nativearcade.NativeArcadeMod;
import cn.piq.nativearcade.layout.NativeCabinetLayout;
import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import cn.piq.nativearcade.world.NativeCabinetBlock;
import cn.piq.nativearcade.world.NativeCabinetBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;

/** Own anchor/item renderers, read-only existing FC native mesh. No FC session or skin renderer. */
@EventBusSubscriber(modid=NativeArcadeMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class NativeCabinetRenderer implements BlockEntityRenderer<NativeCabinetBlockEntity> {
    public static final ModelResourceLocation BODY=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","block/dual_arcade_body"));
    private static Cached cached;
    @FunctionalInterface public interface VideoRenderer {
        /** Original unrotated anchor-local pose; NativeCabinetLayout already rotates the display quad. */
        void render(NativeCabinetBlockEntity cabinet,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay);
    }
    private static volatile VideoRenderer video=(c,p,s,b,l,o)->{};
    public static void setVideoRenderer(VideoRenderer renderer){video=Objects.requireNonNull(renderer);}
    public NativeCabinetRenderer(BlockEntityRendererProvider.Context ignored){}
    @SubscribeEvent public static void registerModels(ModelEvent.RegisterAdditional event){event.register(BODY);}
    @SubscribeEvent public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(NativeArcadeRegistries.CABINET_ENTITY.get(),NativeCabinetRenderer::new);}
    @SubscribeEvent public static void registerItems(RegisterClientExtensionsEvent event){
        event.registerItem(new IClientItemExtensions(){
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new ItemRenderer();return renderer;}
        },NativeArcadeRegistries.CABINET_ITEM.get());
    }
    public static int turns(NativeCabinetBlockEntity machine){
        return switch(machine.getBlockState().getValue(NativeCabinetBlock.FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
    }
    @Override public boolean shouldRenderOffScreen(NativeCabinetBlockEntity machine){return true;}
    @Override public AABB getRenderBoundingBox(NativeCabinetBlockEntity machine){var b=NativeCabinetLayout.bounds(turns(machine));return new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).move(machine.getBlockPos());}
    @Override public void render(NativeCabinetBlockEntity machine,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        poses.pushPose();
        try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*turns(machine)));poses.translate(-.5,0,-.5);
            poses.scale(NativeCabinetLayout.MODEL_SCALE,NativeCabinetLayout.MODEL_SCALE,NativeCabinetLayout.MODEL_SCALE);
            poses.translate(0,NativeCabinetLayout.MODEL_Y_OFFSET,0);draw(poses,buffers,light,overlay);
        }finally{poses.popPose();}
        // Missing video integration intentionally leaves the frozen opaque black screen intact.
        poses.pushPose();try{video.render(machine,partial,poses,buffers,light,overlay);}finally{poses.popPose();}
    }
    private static void draw(PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        BakedModel model=Minecraft.getInstance().getModelManager().getModel(BODY);
        if(cached==null||cached.model()!=model){
            var random=RandomSource.create(0x504951L);var quads=new ArrayList<>(model.getQuads(null,null,random));
            for(Direction side:Direction.values()){random.setSeed(0x504951L);quads.addAll(model.getQuads(null,side,random));}
            cached=new Cached(model,List.copyOf(quads));
        }
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        for(var quad:cached.quads())out.putBulkData(poses.last(),quad,1,1,1,1,light,overlay);
    }
    private record Cached(BakedModel model,List<BakedQuad> quads){}
    private static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        private ItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
        @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
            poses.pushPose();try{
                poses.translate(.5,.5,.5);poses.scale(.40F,.40F,.40F);poses.translate(-1,-1.175,-.5);
                poses.translate(0,NativeCabinetLayout.MODEL_Y_OFFSET,0);draw(poses,buffers,light,overlay);
            }finally{poses.popPose();}
        }
    }
}
