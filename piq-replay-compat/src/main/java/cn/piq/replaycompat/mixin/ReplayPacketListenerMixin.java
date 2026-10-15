package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.replay.*;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.recording.gui.GuiSavingReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.netty.channel.ChannelHandlerContext;

/** The synthetic close method is bytecode-verified and intentionally pinned to ReForgedPlay 0.3. */
@Mixin(value = PacketListener.class, remap = false)
public abstract class ReplayPacketListenerMixin implements RecordingClock {
    @Shadow @Final private ReplayFile replayFile;
    @Shadow @Final private long startTime;
    @Shadow private long lastSentPacket;
    @Shadow private long timePassedWhilePaused;
    @Shadow private volatile boolean serverWasPaused;
    @Override public long piqReplay$recordingTime() {
        return ReplayClock.recordingTime(System.currentTimeMillis(), startTime, timePassedWhilePaused, lastSentPacket, serverWasPaused);
    }
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void piqReplay$begin(CallbackInfo ci) { ReplayBridge.beginRecording(this, this, replayFile); }
    @Inject(method = "channelInactive", at = @At("HEAD"), require = 1)
    private void piqReplay$stop(ChannelHandlerContext context, CallbackInfo ci) { ReplayBridge.stopRecording(this); }
    @Inject(method = "lambda$channelInactive$6", at = @At("HEAD"), require = 1)
    private void piqReplay$finish(GuiSavingReplay screen, CallbackInfo ci) { ReplayBridge.finishRecording(this); }
}
