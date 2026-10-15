package cn.piq.replaycompat.mixin;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.capture.ReplayGuardPolicy;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Gate before NeoForge dispatch, not ClientPacketListener's vanilla debug-payload fallback. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class GuardCustomPayload {
    @Inject(method="handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void piqReplay$receive(ClientboundCustomPayloadPacket packet,CallbackInfo callback) {
        if(ReplayGuardPolicy.blocks(ScreenReplay.playing(),packet.payload().type().id().getNamespace())) callback.cancel();
    }
    @Inject(method="send",at=@At("HEAD"),cancellable=true,require=1)
    private void piqReplay$send(Packet<?> packet,CallbackInfo callback) {
        if(packet instanceof ServerboundCustomPayloadPacket custom
                && ReplayGuardPolicy.blocks(ScreenReplay.playing(),custom.payload().type().id().getNamespace())) callback.cancel();
    }
}
