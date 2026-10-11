package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.api.ScreenReplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="cn.piq.mdhome.client.MdPublicClient",remap=false)
public abstract class GuardMd {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard0(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
    @Inject(method="pump",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard1(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
}
