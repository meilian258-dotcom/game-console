package cn.piq.computer.client.mixin;
import cn.piq.computer.client.FirmwareDisplay;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="cn.piq.fcarcade.client.HomeApplianceClient",remap=false)
abstract class ComputerIdleMixin {
    @Inject(method="drawIdle",at=@At("HEAD"),cancellable=true,remap=false)
    private static void computerIdle(HomeTvBlockEntity tv,PoseStack poses,MultiBufferSource buffers,CallbackInfo info){if(FirmwareDisplay.owns(tv))info.cancel();}
}
