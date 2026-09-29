package cn.piq.j2mearcade;

import cn.piq.j2mearcade.world.J2meArcadeBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class J2meNetwork {
    private J2meNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                J2meSelectGamePayload.TYPE,
                J2meSelectGamePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !player.hasPermissions(2)
                            || player.distanceToSqr(
                                    payload.blockPos().getX() + 0.5,
                                    payload.blockPos().getY() + 0.5,
                                    payload.blockPos().getZ() + 0.5) > 64.0) {
                        return;
                    }
                    if (player.level().getBlockEntity(payload.blockPos())
                            instanceof J2meArcadeBlockEntity arcade) {
                        arcade.setSelectedJar(payload.fileName());
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                "message.piq_j2me_arcade.game_selected", payload.fileName()), false);
                    }
                }));
    }
}
