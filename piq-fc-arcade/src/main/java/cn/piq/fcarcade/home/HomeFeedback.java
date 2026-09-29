package cn.piq.fcarcade.home;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Brief physical-hardware feedback; vanilla owns the action-bar duration and replacement. */
public final class HomeFeedback {
    private HomeFeedback() {}

    public static void show(ServerPlayer player, String key, Object... arguments) {
        player.displayClientMessage(Component.translatable("message.piq_fc_arcade." + key, arguments), true);
    }
}
