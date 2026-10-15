package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.ReplayBridge;
import com.replaymod.replay.ReplayHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An explicit GUI seek invalidates queued audio even if the target is only milliseconds away. */
@Mixin(value=ReplayHandler.class, remap=false)
public abstract class ReplayAudioTimelineMixin {
    @Inject(method="doJump(IZ)V", at=@At("HEAD"), require=1)
    private void piqReplay$jump(int time, boolean retainCamera, CallbackInfo ci) { ReplayBridge.timelineJump(); }
    @Inject(method="setQuickMode(Z)V", at=@At("HEAD"), require=1)
    private void piqReplay$mode(boolean quick, CallbackInfo ci) { ReplayBridge.timelineJump(); }
}
