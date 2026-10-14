package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.capture.CaptureHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Iris batches entities even with shader packs disabled, bypassing BufferSource overrides. */
@Pseudo
@Mixin(targets="net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource", remap=false)
public abstract class CaptureIrisBufferSource {
    @WrapMethod(method="getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    private VertexConsumer piqReplay$buffer(RenderType requestedType, Operation<VertexConsumer> original) {
        // Iris reassigns its parameter to the shader-wrapped type. A RETURN injection sees
        // that replacement, not the ScreenRenderMaterial identity. The wrapper keeps the
        // original argument on the call stack; nested calls and exceptions need no state.
        return CaptureHooks.wrap(requestedType, original.call(requestedType));
    }

    @WrapMethod(method="endBatch(Lnet/minecraft/client/renderer/RenderType;)V")
    private void piqReplay$flush(RenderType type, Operation<Void> original) {
        CaptureHooks.flush(type);
        original.call(type);
    }
}
