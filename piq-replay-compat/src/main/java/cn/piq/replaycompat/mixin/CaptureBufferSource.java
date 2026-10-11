package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.capture.CaptureHooks;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class CaptureBufferSource {
    @Inject(method="getBuffer",at=@At("RETURN"),cancellable=true,require=1)
    private void piqReplay$buffer(RenderType type, CallbackInfoReturnable<VertexConsumer> callback) {
        callback.setReturnValue(CaptureHooks.wrap(type,callback.getReturnValue()));
    }
    @Inject(method="endBatch(Lnet/minecraft/client/renderer/RenderType;)V",at=@At("HEAD"),require=1)
    private void piqReplay$flush(RenderType type, CallbackInfo callback) { CaptureHooks.flush(type); }
}
