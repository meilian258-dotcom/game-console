package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.capture.CaptureHooks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Pseudo
@Mixin(targets="cn.piq.j2mearcade.client.J2meMachineScreenRenderer",remap=false)
public abstract class CaptureJ2meMaterial {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/RenderType;entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),require=1)
    private static RenderType piqReplay$screen(ResourceLocation texture) {
        RenderType type=RenderType.entityCutoutNoCull(texture); CaptureHooks.material(texture,type); return type;
    }
}
