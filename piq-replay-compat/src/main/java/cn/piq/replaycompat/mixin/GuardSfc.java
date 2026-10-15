package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.capture.ReplayRetirement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="cn.piq.sfchome.client.SfcHomeClient",remap=false)
public abstract class GuardSfc {
    @Unique private static final ReplayRetirement piqReplay$retirement = new ReplayRetirement();
    @Shadow private static void closeLocal() { throw new AssertionError(); }
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard0(CallbackInfo callback) {
        if(piqReplay$retirement.block(ScreenReplay.playing(),GuardSfc::closeLocal)) callback.cancel();
    }
    @Inject(method="maybeBegin",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard1(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
}
