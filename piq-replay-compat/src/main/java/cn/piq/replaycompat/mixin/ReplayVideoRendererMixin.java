package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.ReplayBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.replaymod.render.rendering.VideoRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs after the export timeline tick, before its world framebuffer is captured. */
@Mixin(value = VideoRenderer.class, remap = false)
public abstract class ReplayVideoRendererMixin {
    @Shadow public abstract void setFailure(Throwable cause);
    @Shadow public abstract void cancel();
    /** This revision supports audible preview only; export never queues wall-clock sound. */
    @WrapMethod(method="renderVideo()Z")
    private boolean piqReplay$muteExport(Operation<Boolean> original) throws Throwable {
        try { ReplayBridge.beginExport(); return original.call(); }
        finally { ReplayBridge.endExport(); }
    }
    @Inject(method = "updateForNextFrame", at = @At("RETURN"), require = 1)
    private void piqReplay$prepareFrame(CallbackInfoReturnable<Float> cir) {
        if (ReplayBridge.playing() && !ReplayBridge.prepareAt(ReplayBridge.playbackTime())) {
            var failure = new IllegalStateException("屏幕轨未能在导出前就绪，已停止导出以避免静默输出错误画面。");
            ReplayBridge.warn(failure.getMessage(), failure); setFailure(failure); cancel();
        }
    }
}
