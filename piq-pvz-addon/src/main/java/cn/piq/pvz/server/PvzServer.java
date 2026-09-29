package cn.piq.pvz.server;

import cn.piq.fcarcade.home.HomeSystems;
import cn.piq.pvz.net.PvzNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PvzServer {
    public static final ResourceLocation SYSTEM = ResourceLocation.fromNamespaceAndPath("piq_pvz", "pvz");
    private PvzServer() {}
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
            player.displayClientMessage(Component.literal("Pvz 本机原型暂仅创造模式 OP 可测试；未开放多人同步。"), false);
            return;
        }
        PacketDistributor.sendToPlayer(player, new PvzNetwork.Open(player.serverLevel().dimension().location(),
                link.console().getBlockPos(), link.consoleId(), link.television().getBlockPos(), link.televisionId(), link.linkId()));
    }
}
