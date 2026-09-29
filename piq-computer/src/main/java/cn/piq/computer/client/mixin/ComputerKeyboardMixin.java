package cn.piq.computer.client.mixin;
import cn.piq.computer.client.ComputerWorldInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=KeyboardHandler.class,priority=10000)
public abstract class ComputerKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void computer$key(long window,int key,int scan,int action,int mods,CallbackInfo ci){if(ComputerWorldInput.key(window,key,scan,action,mods))ci.cancel();}
    @Inject(method="charTyped",at=@At("HEAD"),cancellable=true)
    private void computer$character(long window,int codepoint,int mods,CallbackInfo ci){if(ComputerWorldInput.character(window,codepoint))ci.cancel();}
}
