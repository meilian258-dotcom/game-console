package cn.piq.replayquick.mixin;

import cn.piq.replayquick.QuickSableScope;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.rar.PacketSink;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.replaymod.replaystudio.rar.state.Entity", remap = false)
public abstract class QuickSableEntityMixin {
    @Redirect(method = "lambda$new$0", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/rar/PacketSink;accept(Ljava/lang/Object;)V", ordinal = 0), require = 1)
    private static void piq$markGeneratedTeleport(PacketSink sink, Object value) throws IOException {
        QuickSableScope.generated((Packet) value, () -> sink.accept((Packet) value));
    }
}
