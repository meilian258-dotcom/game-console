package cn.piq.fcarcade.mixin;

import cn.piq.retro.client.KeyboardInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not let a controlled game key open a screen before the post-input event can run. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void piq$keyboard(long window,int key,int scan,int action,int modifiers,CallbackInfo ci){
        if(KeyboardInput.intercept(window,key,scan,action,modifiers))ci.cancel();
    }
}
