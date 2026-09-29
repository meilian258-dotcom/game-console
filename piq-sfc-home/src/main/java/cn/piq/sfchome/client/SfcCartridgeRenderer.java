// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import com.mojang.blaze3d.vertex.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/** Existing item model and transforms are retained; only its label gains a dynamic texture. */
@EventBusSubscriber(modid="piq_sfc_home",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class SfcCartridgeRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation BASE=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","item/cartridge"));
    private static final ModelResourceLocation ITEM=new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","cartridge"),"inventory");
    private SfcCartridgeRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(SfcCartridgeCovers::initialize);}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e){e.register(BASE);}
    @SubscribeEvent public static void bake(ModelEvent.ModifyBakingResult e){BakedModel original=e.getModels().get(ITEM);if(original!=null)e.getModels().put(ITEM,new ItemModel(original));}
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent e){e.registerItem(new IClientItemExtensions(){private BlockEntityWithoutLevelRenderer renderer;@Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new SfcCartridgeRenderer();return renderer;}},SfcHomeRegistries.CARTRIDGE.get());}
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        SfcHardwareMesh.draw("cartridge",poses,buffers,light,overlay);
        label(stack,null,false,poses,buffers,light,overlay);
    }
    static void label(ItemStack stack,BlockPos console,boolean inserted,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var texture=SfcCartridgeCovers.texture(SfcCartridgeData.coverSha(stack),console);if(texture==null)return;
        var f=SfcCoverGeometry.label(inserted);var out=buffers.getBuffer(RenderType.entityCutoutNoCull(texture));var pose=poses.last();
        vertex(out,pose,f.right(),f.bottom(),f.z(),0,1,light,overlay);vertex(out,pose,f.left(),f.bottom(),f.z(),1,1,light,overlay);
        vertex(out,pose,f.left(),f.top(),f.z(),1,0,light,overlay);vertex(out,pose,f.right(),f.top(),f.z(),0,0,light,overlay);
    }
    private static void vertex(VertexConsumer out,PoseStack.Pose p,float x,float y,float z,float u,float v,int light,int overlay){out.addVertex(p.pose(),x,y,z).setColor(255,255,255,255).setUv(u,v).setOverlay(overlay).setLight(light).setNormal(p,0,0,-1);}
    private static final class ItemModel extends BakedModelWrapper<BakedModel>{
        ItemModel(BakedModel original){super(original);}
        @Override public boolean isCustomRenderer(){return true;}
        @Override public BakedModel applyTransform(ItemDisplayContext context,PoseStack pose,boolean left){originalModel.applyTransform(context,pose,left);return this;}
        @Override public List<BakedModel> getRenderPasses(ItemStack stack,boolean fabulous){return List.of(this);}
    }
}
