package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.capture.CaptureHooks;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(DynamicTexture.class)
public abstract class CaptureDynamicTexture extends AbstractTexture {
    @Inject(method="close",at=@At("HEAD"),require=1)
    private void piqReplay$release(CallbackInfo callback) {
        int textureId = id;
        if(RenderSystem.isOnRenderThread()) CaptureHooks.releaseRaw(textureId);
        else RenderSystem.recordRenderCall(() -> CaptureHooks.releaseRaw(textureId));
    }
}
