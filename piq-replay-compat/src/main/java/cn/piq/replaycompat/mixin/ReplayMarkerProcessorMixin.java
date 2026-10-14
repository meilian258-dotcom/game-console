package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.MarkerTrackBridge;
import cn.piq.replaycompat.replay.AudioMarkerBridge;
import com.replaymod.core.files.ReplayFilesService;
import com.replaymod.editor.gui.MarkerProcessor;
import com.replaymod.replaystudio.replay.ReplayFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(value = MarkerProcessor.class, remap = false)
public abstract class ReplayMarkerProcessorMixin {
    @Inject(method = "apply", at = @At("HEAD"), require = 1)
    private static void piqReplay$begin(Path path, Consumer<Float> progress, CallbackInfoReturnable<?> cir) { MarkerTrackBridge.begin(); AudioMarkerBridge.begin(); }
    @Redirect(method = "apply", at = @At(value = "INVOKE", target = "Lcom/replaymod/core/files/ReplayFilesService;open(Ljava/nio/file/Path;)Lcom/replaymod/replaystudio/replay/ReplayFile;"), require = 1)
    private static ReplayFile piqReplay$input(ReplayFilesService service, Path path) throws IOException {
        ReplayFile file = service.open(path); MarkerTrackBridge.input(file); AudioMarkerBridge.input(file); return file;
    }
    @Redirect(method = "apply", at = @At(value = "INVOKE", target = "Lcom/replaymod/replaystudio/replay/ReplayFile;save()V"), require = 1)
    private static void piqReplay$save(ReplayFile file) throws IOException { MarkerTrackBridge.beforeSave(file); AudioMarkerBridge.beforeSave(file); file.save(); }
    @Redirect(method = "apply", at = @At(value = "INVOKE", target = "Lcom/replaymod/replaystudio/replay/ReplayFile;close()V"), require = 1)
    private static void piqReplay$close(ReplayFile file) throws IOException { MarkerTrackBridge.closing(file); AudioMarkerBridge.closing(file); file.close(); }
    @Inject(method = "apply", at = @At("RETURN"), require = 1)
    private static void piqReplay$end(Path path, Consumer<Float> progress, CallbackInfoReturnable<?> cir) { MarkerTrackBridge.end(); AudioMarkerBridge.end(); }
}
