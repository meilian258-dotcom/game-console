package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.ReplayBridge;
import com.replaymod.replay.ReplayModReplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ReForgedPlay 0.3 uses this after failed endReplay(), without dispatching its closed callback. */
@Mixin(value = ReplayModReplay.class, remap = false)
public abstract class ReplayForceStopMixin {
    @Inject(method = "forcefullyStopReplay", at = @At("HEAD"), require = 1)
    private void piqReplay$clearAfterForcedStop(CallbackInfo ci) { ReplayBridge.forcefullyStopPlayback(); }
}
