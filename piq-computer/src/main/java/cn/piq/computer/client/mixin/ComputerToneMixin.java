package cn.piq.computer.client.mixin;
import cn.piq.computer.client.FirmwareDisplay;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="cn.piq.fcarcade.client.TelevisionTone",remap=false)
abstract class ComputerToneMixin {
    @Inject(method="valid",at=@At("HEAD"),cancellable=true,remap=false)
    private static void computerTone(HomeTvBlockEntity tv,CallbackInfoReturnable<Boolean> info){if(FirmwareDisplay.owns(tv))info.setReturnValue(false);}
}
