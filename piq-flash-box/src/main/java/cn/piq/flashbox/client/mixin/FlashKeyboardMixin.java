package cn.piq.flashbox.client.mixin;

import cn.piq.flashbox.client.FlashWorldInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture before ordinary mod hotkeys; outside explicit capture the original handler is untouched. */
@Mixin(value=KeyboardHandler.class, priority=10000)
public abstract class FlashKeyboardMixin {
    @Inject(method="keyPress", at=@At("HEAD"), cancellable=true)
    private void flashbox$key(long window, int key, int scan, int action, int modifiers, CallbackInfo ci) {
        if (FlashWorldInput.intercept(window, key, scan, action, modifiers)) ci.cancel();
    }
}
