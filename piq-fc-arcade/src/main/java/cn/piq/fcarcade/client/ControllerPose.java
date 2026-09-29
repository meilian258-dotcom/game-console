package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeControllerData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Public NeoForge hooks, scoped strictly to FC controllers and their otherwise empty support hand. */
final class ControllerPose {
    private static boolean registered;
    private static final ThreadLocal<Boolean> DRAWING_FIRST_ARMS = ThreadLocal.withInitial(() -> false);
    private ControllerPose() {}

    static void register() {
        if (!registered) { registered = true; NeoForge.EVENT_BUS.addListener(ControllerPose::renderHands); ClientControllerAnimation.register(); }
    }
    static HumanoidModel.ArmPose armPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
        if (!eligible(entity, stack)) return null;
        boolean two = ControllerPoseLayout.twoHands(true, entity.getItemInHand(other(hand)).isEmpty());
        return (two ? ControllerArmPoseParameters.TWO_HANDS : ControllerArmPoseParameters.SINGLE_HAND).getValue();
    }
    private static boolean eligible(LivingEntity entity, ItemStack stack) {
        return ControllerPoseLayout.eligible(HomeControllerData.isController(stack), entity instanceof Player,
                entity.isAlive(), entity.isUsingItem(), entity.isVisuallySwimming(), entity.isFallFlying());
    }
    private static InteractionHand other(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }
    static boolean firstTransform(PoseStack poses, LocalPlayer player, HumanoidArm arm, ItemStack stack,
                                  float equip, float swing) {
        if (!HomeControllerData.isController(stack)) return false;
        InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        boolean two = ControllerPoseLayout.twoHands(eligible(player, stack), player.getItemInHand(other(hand)).isEmpty());
        applyFirst(poses, ControllerPoseLayout.first(arm == HumanoidArm.RIGHT, two, equip, swing));
        // ItemRenderer still applies the JSON display and its one (-.5,-.5,-.5) center translation.
        return true;
    }
    private static void applyFirst(PoseStack poses, ControllerPoseLayout.First transform) {
        poses.translate(transform.x(), transform.y(), transform.z());
        poses.mulPose(Axis.XP.rotationDegrees((float) transform.pitch()));
        poses.mulPose(Axis.YP.rotationDegrees((float) transform.yaw()));
    }
    static void applyArms(HumanoidModel<?> model, HumanoidArm holding, boolean two) {
        if (DRAWING_FIRST_ARMS.get()) {
            // PlayerRenderer.renderHand calls setupAnim(age=0), then sets xRot=0.
            // Cancel that exact age=0 vanilla bob so the public skin/sleeve renderer is neutral.
            neutralFirstArm(model.rightArm, true);
            neutralFirstArm(model.leftArm, false);
            return;
        }
        poseArm(holding == HumanoidArm.RIGHT ? model.rightArm : model.leftArm, holding == HumanoidArm.RIGHT, two);
        if (two) poseArm(holding == HumanoidArm.RIGHT ? model.leftArm : model.rightArm, holding != HumanoidArm.RIGHT, true);
    }
    private static void neutralFirstArm(ModelPart arm, boolean right) {
        arm.xRot = 0; arm.yRot = 0; arm.zRot = right ? -.1f : .1f;
    }
    private static void poseArm(ModelPart arm, boolean right, boolean two) {
        arm.xRot = (float) ControllerPoseLayout.THIRD_ARM_PITCH;
        arm.yRot = two ? 0 : (float) (ControllerPoseLayout.SINGLE_ARM_YAW * (right ? 1 : -1));
        // Inward wrists meet the original .6-size controller; compensate vanilla's age=0 idle bob.
        arm.zRot = (float) ((right ? -1 : 1) * (ControllerPoseLayout.THIRD_ARM_INWARD_ROLL + .1));
        // No pivot/visibility/player state changes; vanilla crouch, walking and sleeves remain owned by Minecraft.
    }
    private static void renderHands(RenderHandEvent event) {
        if (event.isCanceled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.isInvisible() || player.isScoping()) return;
        InteractionHand holding;
        if (eligible(player, player.getMainHandItem()) && player.getOffhandItem().isEmpty()) holding = InteractionHand.MAIN_HAND;
        else if (eligible(player, player.getOffhandItem()) && player.getMainHandItem().isEmpty()) holding = InteractionHand.OFF_HAND;
        else return; // Never take the arm, animation or render event belonging to another held object.
        if (event.getHand() != holding) {
            if (event.getItemStack().isEmpty() && player.getItemInHand(event.getHand()).isEmpty()) event.setCanceled(true);
            return;
        }
        if (!HomeControllerData.isController(event.getItemStack())
                || !ItemStack.isSameItemSameComponents(event.getItemStack(), player.getItemInHand(holding))) return;
        if (!(minecraft.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;
        PoseStack poses = event.getPoseStack();
        poses.pushPose();
        boolean previous = DRAWING_FIRST_ARMS.get();
        try {
            HumanoidArm arm = holding == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
            applyFirst(poses, ControllerPoseLayout.first(arm == HumanoidArm.RIGHT, true, event.getEquipProgress(), event.getSwingProgress()));
            DRAWING_FIRST_ARMS.set(true);
            for (HumanoidArm side : HumanoidArm.values()) {
                var transform = ControllerPoseLayout.firstArm(side == HumanoidArm.RIGHT,ClientArcadeEvents.controllerHandSize());
                poses.pushPose();
                try {
                    poses.translate(transform.x(), transform.y(), transform.z());
                    poses.mulPose(Axis.ZP.rotationDegrees((float) transform.roll()));
                    poses.mulPose(Axis.XP.rotationDegrees((float) transform.pitch()));
                    poses.scale((float) transform.scale(), (float) transform.scale(), (float) transform.scale());
                    if (side == HumanoidArm.RIGHT) renderer.renderRightHand(poses, event.getMultiBufferSource(), event.getPackedLight(), player);
                    else renderer.renderLeftHand(poses, event.getMultiBufferSource(), event.getPackedLight(), player);
                } finally { poses.popPose(); }
            }
        } finally { DRAWING_FIRST_ARMS.set(previous); poses.popPose(); }
        // Do not cancel the controller event: vanilla continues to our item extension and BEWLR exactly once.
    }
}
