package cn.piq.fcarcade.client.zapper;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.HomeZapperItem;
import cn.piq.fcarcade.layout.ZapperPoseLayout;
import cn.piq.fcarcade.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import java.util.ArrayList;
import java.util.List;

/** Only body + articulated trigger. The stand, connector and coiled cable stay off the hand. */
public final class ZapperItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation BODY=model("body"),TRIGGER=model("trigger");
    private Cached body,trigger;
    private record Cached(BakedModel model,List<BakedQuad> quads) {}
    private static ModelResourceLocation model(String part){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"item/zapper/"+part));}
    private ZapperItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    static void register(IEventBus bus){bus.addListener(ZapperItemRenderer::models);bus.addListener(ZapperItemRenderer::items);}
    private static void models(ModelEvent.RegisterAdditional event){event.register(BODY);event.register(TRIGGER);}
    private static void items(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions(){
            private ZapperItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new ZapperItemRenderer();return renderer;}
            @Override public HumanoidModel.ArmPose getArmPose(LivingEntity entity,InteractionHand hand,ItemStack stack) {
                return entity instanceof Player&&entity.isAlive()&&!entity.isUsingItem()&&!entity.isVisuallySwimming()&&!entity.isFallFlying()
                        &&stack.getItem()instanceof HomeZapperItem?ZapperArmPoseParameters.AIM.getValue():null;
            }
            @Override public boolean applyForgeHandTransform(PoseStack poses,LocalPlayer player,HumanoidArm arm,ItemStack stack,float partial,float equip,float swing) {
                var p=ZapperPoseLayout.first(arm==HumanoidArm.RIGHT,equip);poses.translate(p.x(),p.y(),p.z());return true;
            }
        },ModItems.FC_ZAPPER.get());
    }
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        var view=switch(context){
            case FIRST_PERSON_LEFT_HAND->ZapperPoseLayout.View.FIRST_LEFT;case FIRST_PERSON_RIGHT_HAND->ZapperPoseLayout.View.FIRST_RIGHT;
            case THIRD_PERSON_LEFT_HAND->ZapperPoseLayout.View.THIRD_LEFT;case THIRD_PERSON_RIGHT_HAND->ZapperPoseLayout.View.THIRD_RIGHT;
            case GUI->ZapperPoseLayout.View.GUI;case GROUND->ZapperPoseLayout.View.GROUND;default->ZapperPoseLayout.View.FIXED;
        };
        var p=ZapperPoseLayout.item(view);poses.pushPose();
        try {
            poses.translate(p.x(),p.y(),p.z());poses.mulPose(Axis.YP.rotationDegrees((float)p.yaw()));
            poses.mulPose(Axis.XP.rotationDegrees((float)p.pitch()));poses.mulPose(Axis.ZP.rotationDegrees((float)p.roll()));
            poses.scale((float)p.scale(),(float)p.scale(),(float)p.scale());poses.translate(-p.origin().x()/16,-p.origin().y()/16,-p.origin().z()/16);
            body=cache(BODY,body);trigger=cache(TRIGGER,trigger);draw(body,poses,buffers,light,overlay);
            boolean held=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                    ||context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            var pivot=ZapperPoseLayout.TRIGGER_PIVOT;poses.translate(pivot.x()/16,pivot.y()/16,pivot.z()/16);
            poses.mulPose(Axis.ZP.rotationDegrees((float)(ZapperPoseLayout.TRIGGER_DEGREES*(held?ZapperClient.trigger(stack):0))));
            poses.translate(-pivot.x()/16,-pivot.y()/16,-pivot.z()/16);draw(trigger,poses,buffers,light,overlay);
        } finally {poses.popPose();}
    }
    private static Cached cache(ModelResourceLocation id,Cached old) {
        var model=Minecraft.getInstance().getModelManager().getModel(id);if(old!=null&&old.model()==model)return old;
        var random=RandomSource.create(0x504951L);var quads=new ArrayList<>(model.getQuads(null,null,random));
        for(Direction side:Direction.values()){random.setSeed(0x504951L);quads.addAll(model.getQuads(null,side,random));}
        return new Cached(model,List.copyOf(quads));
    }
    private static void draw(Cached cached,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        for(var quad:cached.quads())consumer.putBulkData(poses.last(),quad,1,1,1,1,light,overlay);
    }
}
