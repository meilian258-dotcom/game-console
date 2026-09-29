package cn.piq.pvz.client.mixin;

import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.pvz.client.PvzClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="cn.piq.fcarcade.client.TelevisionTone",remap=false)
abstract class PvzToneMixin {
    @Inject(method="valid",at=@At("HEAD"),cancellable=true,remap=false)
    private static void pvzBoxTone(HomeTvBlockEntity tv,CallbackInfoReturnable<Boolean> info) {
        if(PvzClient.ownsDisplay(tv)) info.setReturnValue(false);
    }
}
