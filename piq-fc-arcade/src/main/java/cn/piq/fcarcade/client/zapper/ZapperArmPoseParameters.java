package cn.piq.fcarcade.client.zapper;

import cn.piq.fcarcade.layout.ZapperPoseLayout;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/** Enum construction parameters only: no Minecraft singleton or registry lookup. */
public final class ZapperArmPoseParameters {
    public static final EnumProxy<HumanoidModel.ArmPose> AIM=new EnumProxy<>(HumanoidModel.ArmPose.class,false,
            (IArmPoseTransformer)(model,entity,arm)->{
                var pose=ZapperPoseLayout.arm(arm==HumanoidArm.RIGHT,model.head.xRot,model.head.yRot);
                var part=arm==HumanoidArm.RIGHT?model.rightArm:model.leftArm;
                part.xRot=(float)pose.pitch();part.yRot=(float)pose.yaw();part.zRot=(float)pose.roll();
            });
    private ZapperArmPoseParameters() {}
}
