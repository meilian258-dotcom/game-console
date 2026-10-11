package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.capture.CaptureHooks;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Pseudo
@Mixin(targets="cn.piq.computer.client.ComputerStreams",remap=false)
public abstract class CaptureComputerStreamPixels {
    @Redirect(method="texture",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;bindTexture(I)V"),require=1)
    private static void piqReplay$bind(int texture) {
        RenderSystem.bindTexture(texture); CaptureHooks.rawBind(texture);
    }
    @Redirect(method="texture",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_texSubImage2D(IIIIIIIIJ)V"),require=1)
    private static void piqReplay$upload(int target,int level,int x,int y,int width,int height,int format,int type,long pointer) {
        if(target==3553&&level==0&&x==0&&y==0&&format==6408&&type==5121) CaptureHooks.rawUpload(width,height,pointer);
        GlStateManager._texSubImage2D(target,level,x,y,width,height,format,type,pointer);
    }
}
