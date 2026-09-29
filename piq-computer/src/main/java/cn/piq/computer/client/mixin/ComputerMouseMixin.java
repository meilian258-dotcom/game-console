package cn.piq.computer.client.mixin;

import cn.piq.computer.client.ComputerWorldInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consume accumulated raw displacement before vanilla sensitivity/camera turning. */
@Mixin(value=MouseHandler.class,priority=10000)
public abstract class ComputerMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;
    @Inject(method="turnPlayer",at=@At("HEAD"),cancellable=true)
    private void computer$pointer(double elapsed,CallbackInfo ci){
        if(ComputerWorldInput.relativeMotion(accumulatedDX,accumulatedDY)){
            accumulatedDX=accumulatedDY=0;smoothTurnX.reset();smoothTurnY.reset();ci.cancel();
        }
    }
}
