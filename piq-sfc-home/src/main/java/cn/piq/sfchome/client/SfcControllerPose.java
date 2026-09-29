// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfchome.data.SfcControllerData;
import cn.piq.fcarcade.client.ControllerArmPoseParameters;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3f;

/** Only the SFC controller and an actually empty support hand are replaced. No gameplay/input changes. */
@EventBusSubscriber(modid="piq_sfc_home",value=Dist.CLIENT)
public final class SfcControllerPose {
    private SfcControllerPose(){}
    private static final ThreadLocal<Boolean> FIRST_ARMS=ThreadLocal.withInitial(()->false);
    /** Reuse the core's two-arm enum hook so vanilla applies it after normal walk/crouch posing. */
    static HumanoidModel.ArmPose armPose(LivingEntity entity,InteractionHand hand,ItemStack stack){
        if(FIRST_ARMS.get()||!(entity instanceof Player)||!SfcControllerData.isController(stack)||!entity.isAlive()
                ||entity.isUsingItem()||entity.isVisuallySwimming()||entity.isFallFlying())return null;
        var other=hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        return (entity.getItemInHand(other).isEmpty()?ControllerArmPoseParameters.TWO_HANDS:ControllerArmPoseParameters.SINGLE_HAND).getValue();
    }
    static void thirdTransform(PoseStack poses,boolean left){
        // Match the FC wrist-centred rig, then turn SFC's +Y button face to canonical +Z.
        new ItemTransform(new Vector3f(-8.23849004f,-25.80682901f,28.91855928f),
                new Vector3f(-2.97557615f/16,-2.0133141f/16,-1.16238744f/16),new Vector3f(.6f)).apply(left,poses);
        poses.mulPose(Axis.XP.rotationDegrees(90));poses.mulPose(Axis.YP.rotationDegrees(180));
    }
    private static boolean eligible(LocalPlayer p,InteractionHand hand){
        InteractionHand other=hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        return SfcControllerPoseLayout.eligible(SfcControllerData.isController(p.getItemInHand(hand)),p.getItemInHand(other).isEmpty(),p.isAlive(),p.isInvisible(),p.isScoping(),p.isVisuallySwimming(),p.isFallFlying());
    }
    @SubscribeEvent public static void renderHands(RenderHandEvent event){
        if(event.isCanceled())return;var mc=Minecraft.getInstance();LocalPlayer player=mc.player;if(player==null)return;
        InteractionHand holding=eligible(player,InteractionHand.MAIN_HAND)?InteractionHand.MAIN_HAND:eligible(player,InteractionHand.OFF_HAND)?InteractionHand.OFF_HAND:null;
        if(holding==null)return;
        if(event.getHand()!=holding){if(event.getItemStack().isEmpty()&&player.getItemInHand(event.getHand()).isEmpty())event.setCanceled(true);return;}
        if(!SfcControllerData.isController(event.getItemStack())||!ItemStack.isSameItemSameComponents(event.getItemStack(),player.getItemInHand(holding)))return;
        if(!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer))return;
        event.setCanceled(true);PoseStack poses=event.getPoseStack();poses.pushPose();boolean previous=FIRST_ARMS.get();
        try{
            FIRST_ARMS.set(true);
            var rig=SfcControllerPoseLayout.rig(event.getEquipProgress(),event.getSwingProgress());
            poses.translate(0,rig.y(),rig.z());poses.mulPose(Axis.XP.rotationDegrees((float)rig.pitch()));
            for(HumanoidArm arm:HumanoidArm.values()){
                var t=SfcControllerPoseLayout.arm(arm==HumanoidArm.RIGHT);poses.pushPose();
                try{poses.translate(t.x(),t.y(),t.z());poses.mulPose(Axis.ZP.rotationDegrees((float)t.roll()));poses.mulPose(Axis.XP.rotationDegrees((float)t.pitch()));poses.scale((float)t.scale(),(float)t.scale(),(float)t.scale());
                    if(arm==HumanoidArm.RIGHT)renderer.renderRightHand(poses,event.getMultiBufferSource(),event.getPackedLight(),player);else renderer.renderLeftHand(poses,event.getMultiBufferSource(),event.getPackedLight(),player);
                }finally{poses.popPose();}
            }
            poses.pushPose();
            try{
                // Canonical SFC buttons face +Y. Turn them to the shared rig's +Z, then incline the whole rig.
                poses.mulPose(Axis.XP.rotationDegrees(90));poses.mulPose(Axis.YP.rotationDegrees(180));
                float scale=(float)SfcControllerPoseLayout.CONTROLLER_SCALE;poses.scale(scale,scale,scale);poses.translate(-.5,-.5,-.5);
                SfcHardwareMesh.draw("controller",poses,event.getMultiBufferSource(),event.getPackedLight(),OverlayTexture.NO_OVERLAY,SfcHomeClient.visualInputMask());
            }finally{poses.popPose();}
        }finally{FIRST_ARMS.set(previous);poses.popPose();}
    }
}
