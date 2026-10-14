package cn.piq.replayquick.mixin;

import cn.piq.replayquick.QuickSableState;
import com.replaymod.replaystudio.protocol.Packet;
import io.netty.channel.ChannelPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.replaymod.replay.QuickReplaySender$1", remap = false)
public abstract class QuickSableDispatchMixin {
    @Redirect(method = "dispatch", at = @At(value = "INVOKE",
            target = "Lio/netty/channel/ChannelPipeline;fireChannelRead(Ljava/lang/Object;)Lio/netty/channel/ChannelPipeline;"), require = 1)
    private ChannelPipeline piq$completeGeneratedPlayerPosition(ChannelPipeline pipeline, Object value, Packet original) {
        return QuickSableState.dispatch(pipeline, value, original);
    }
}
