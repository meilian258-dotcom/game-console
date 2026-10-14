package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.api.ScreenReplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="cn.piq.j2mearcade.client.ClientJ2meArcade",remap=false)
public abstract class GuardJ2me {
    @Inject(method={"open","openMachine","openSelector"},at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$open(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
    @Inject(method="onRenderFrame",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard0(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
    @Inject(method="startMachine",at=@At("HEAD"),cancellable=true,require=1)
    private static void piqReplay$guard1(CallbackInfo callback) {
        if(ScreenReplay.playing()) callback.cancel();
    }
}
