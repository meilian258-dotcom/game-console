package cn.piq.fcarcade.client;

import net.minecraft.client.model.HumanoidModel;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/** Early enum parameters only: no Minecraft singleton, registry or renderer initialization. */
public final class ControllerArmPoseParameters {
    public static final EnumProxy<HumanoidModel.ArmPose> TWO_HANDS = new EnumProxy<>(
            HumanoidModel.ArmPose.class, true,
            (IArmPoseTransformer) (model, entity, arm) -> ControllerPose.applyArms(model, arm, true));
    public static final EnumProxy<HumanoidModel.ArmPose> SINGLE_HAND = new EnumProxy<>(
            HumanoidModel.ArmPose.class, false,
            (IArmPoseTransformer) (model, entity, arm) -> ControllerPose.applyArms(model, arm, false));
    private ControllerArmPoseParameters() {}
}
