package cn.piq.flashbox.server;

import cn.piq.fcarcade.home.HomeSystems;
import cn.piq.flashbox.net.FlashBoxNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FlashBoxServer {
    public static final ResourceLocation SYSTEM = ResourceLocation.fromNamespaceAndPath("piq_flash_box", "flash");
    private FlashBoxServer() {}
    public static void register() {
        HomeSystems.register(SYSTEM, new HomeSystems.ServerHooks() {
            @Override public void onInteract(ServerPlayer player, InteractionHand hand,
                                             HomeSystems.Connection link, BlockHitResult hit) {
                if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()
                        || !player.getOffhandItem().isEmpty()) return;
                open(player, link);
            }
            @Override public boolean onPowerOn(ServerPlayer player, HomeSystems.Connection link) {
                open(player, link);
                // A local file chooser is not a public multiplayer playback session.
                return false;
            }
        });
    }
    private static void open(ServerPlayer player, HomeSystems.Connection link) {
        if (!HomeSystems.isCurrent(link) || !player.hasPermissions(2) || !player.isCreative()) {
            player.displayClientMessage(Component.literal("Flash 本机原型暂仅创造模式 OP 可测试；未开放多人同步。"), false);
            return;
        }
        PacketDistributor.sendToPlayer(player, new FlashBoxNetwork.Open(player.serverLevel().dimension().location(),
                link.console().getBlockPos(), link.consoleId(), link.television().getBlockPos(), link.televisionId(), link.linkId()));
    }
}
