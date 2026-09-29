package cn.piq.pvz.client.mixin;

import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.pvz.client.PvzClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only overlay gate for the exact local Pvz texture; no server state alteration. */
@Mixin(targets="cn.piq.fcarcade.client.HomeApplianceClient",remap=false)
abstract class PvzIdleMixin {
    @Inject(method="drawIdle",at=@At("HEAD"),cancellable=true,remap=false)
    private static void pvzBoxIdle(HomeTvBlockEntity tv,PoseStack poses,MultiBufferSource buffers,CallbackInfo info) {
        if(PvzClient.ownsDisplay(tv)) info.cancel();
    }
}
