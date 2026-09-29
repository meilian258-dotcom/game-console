package cn.piq.flashbox.client.mixin;

import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.flashbox.client.FlashBoxClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="cn.piq.fcarcade.client.TelevisionTone",remap=false)
abstract class FlashToneMixin {
    @Inject(method="valid",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flashBoxTone(HomeTvBlockEntity tv,CallbackInfoReturnable<Boolean> info) {
        if(FlashBoxClient.ownsDisplay(tv)) info.setReturnValue(false);
    }
}
