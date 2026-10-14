package cn.piq.replayquick.mixin;

import cn.piq.replayquick.QuickSableAccess;
import cn.piq.replayquick.QuickSableState;
import com.replaymod.replay.QuickReplaySender;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replaystudio.rar.RandomAccessReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.IOException;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = QuickReplaySender.class, remap = false)
public abstract class QuickSableSenderMixin implements QuickSableAccess {
    @Unique private QuickSableState piq$sable;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void piq$ownIndex(ReplayModReplay mod, ReplayFile file, CallbackInfo ci) {
        piq$sable = new QuickSableState((QuickReplaySender) (Object) this, file);
    }

    @Override public QuickSableState piq$sableState() { return piq$sable; }

    @Redirect(method = "lambda$initialize$2", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/rar/RandomAccessReplay;load(Ljava/util/function/Consumer;)V"), require = 1)
    private void piq$loadFlags(RandomAccessReplay replay, Consumer<Double> progress) throws IOException {
        piq$sable.load();
        replay.load(progress);
    }

    @Redirect(method = "lambda$sendPacketsTill$5", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/rar/RandomAccessReplay;seek(I)V"), require = 1)
    private void piq$seekWithTarget(RandomAccessReplay replay, int target) throws IOException {
        piq$sable.seek(replay, target);
    }
}
