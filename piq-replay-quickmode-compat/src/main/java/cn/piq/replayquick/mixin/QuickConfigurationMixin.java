package cn.piq.replayquick.mixin;

import com.replaymod.replay.ReplayModReplay;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve an established handshake only during ReplayStudio's synthetic reconfiguration. */
@Mixin(value = ClientConfigurationPacketListenerImpl.class, remap = false)
public abstract class QuickConfigurationMixin {
    @Shadow private ConnectionType connectionType;
    @Shadow private boolean initializedConnection;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void piq$preserveQuickReplayHandshake(Minecraft minecraft, Connection connection,
                                                 CommonListenerCookie cookie, CallbackInfo ci) {
        if (!(connection.channel() instanceof EmbeddedChannel)) return;
        var mod = ReplayModReplay.instance;
        var replay = mod == null ? null : mod.getReplayHandler();
        if (replay == null || !replay.isQuickMode()) return;
        // Do not invent a handshake for initial login or a vanilla recording.
        if (cookie.connectionType() != ConnectionType.NEOFORGE
                || ChannelAttributes.getConnectionType(connection) != ConnectionType.NEOFORGE
                || ChannelAttributes.getPayloadSetup(connection) == null) return;
        this.connectionType = ConnectionType.NEOFORGE;
        this.initializedConnection = true;
        LoggerFactory.getLogger("PIQ-ReplayQuickMode").info(
                "Preserved established NeoForge handshake for quick replay reconfiguration");
    }
}
