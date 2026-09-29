// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfchome.registry.SfcHomeRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import java.util.List;

/** Item transforms remain vanilla; only the SFC console/controller geometry is custom. */
@EventBusSubscriber(modid="piq_sfc_home",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class SfcHardwareItems {
    private SfcHardwareItems(){}
    @SubscribeEvent public static void bake(ModelEvent.ModifyBakingResult event){
        for(String name:new String[]{"console","controller"}){
            var id=new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath("piq_sfc_home",name),"inventory");
            BakedModel old=event.getModels().get(id);if(old!=null)event.getModels().put(id,new ItemModel(old,name.equals("controller")));
        }
    }
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent event){
        event.registerItem(new IClientItemExtensions(){private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new HardwareItemRenderer();return renderer;}
            @Override public HumanoidModel.ArmPose getArmPose(LivingEntity entity,InteractionHand hand,ItemStack stack){return SfcControllerPose.armPose(entity,hand,stack);}
        },SfcHomeRegistries.CONSOLE_ITEM.get(),SfcHomeRegistries.CONTROLLER.get());
    }
    private static final class HardwareItemRenderer extends BlockEntityWithoutLevelRenderer {
        HardwareItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
        @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
            if(stack.is(SfcHomeRegistries.CONTROLLER.get())){
                var player=Minecraft.getInstance().player;
                int mask=context.firstPerson()&&player!=null&&ItemStack.isSameItemSameComponents(stack,player.getMainHandItem())?SfcHomeClient.visualInputMask():0;
                SfcHardwareMesh.draw("controller",poses,buffers,light,overlay,mask);
            }
            else for(String group:new String[]{"body","p1_docked","p2_docked","slot_cover"})SfcHardwareMesh.draw(group,poses,buffers,light,overlay);
        }
    }
    private static final class ItemModel extends BakedModelWrapper<BakedModel>{
        private final boolean controller;
        ItemModel(BakedModel original,boolean controller){super(original);this.controller=controller;}
        @Override public boolean isCustomRenderer(){return true;}
        @Override public BakedModel applyTransform(ItemDisplayContext context,PoseStack poses,boolean left){
            if(controller&&(context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND))SfcControllerPose.thirdTransform(poses,left);
            else originalModel.applyTransform(context,poses,left);return this;
        }
        @Override public List<BakedModel> getRenderPasses(ItemStack stack,boolean fabulous){return List.of(this);}
    }
}
