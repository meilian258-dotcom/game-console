package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.ReplayBridge;
import cn.piq.replaycompat.replay.ReplayEntryRejection;
import cn.piq.retro.replay.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Enter read-only mode before ReplayHandler can process its first recorded device packet. */
@Mixin(value=ReplayModReplay.class,remap=false)
public abstract class ReplayOpenGuardMixin {
    @WrapOperation(method="startReplay(Lcom/replaymod/replaystudio/replay/ReplayFile;ZZ)Lcom/replaymod/replay/ReplayHandler;",
            at=@At(value="NEW",target="(Lcom/replaymod/replaystudio/replay/ReplayFile;Z)Lcom/replaymod/replay/ReplayHandler;"),require=1)
    private ReplayHandler piqReplay$open(ReplayFile file, boolean async, Operation<ReplayHandler> original) throws Throwable {
        // A rejected request must not invalidate a normal game's save/worker generation.
        try {
            ReplayGuard.checkEntry();
        } catch (IllegalStateException rejection) {
            throw ReplayEntryRejection.reject((ReplayModReplay)(Object)this, rejection);
        }
        ReplayGuard.enterReplay();
        try {
            try {
                ReplayGuard.checkEntry();
            } catch (IllegalStateException rejection) {
                throw ReplayEntryRejection.reject((ReplayModReplay)(Object)this, rejection);
            }
            return original.call(file,async);
        } catch (Throwable failure) {
            ReplayBridge.forcefullyStopPlayback();
            throw failure;
        }
    }
}
