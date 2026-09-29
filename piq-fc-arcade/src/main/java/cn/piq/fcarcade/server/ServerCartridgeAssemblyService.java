package cn.piq.fcarcade.server;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Physical crafting is server-authoritative and never gains a creative-mode copy exception. */
public final class ServerCartridgeAssemblyService {
    private static final Map<MinecraftServer, Map<UUID, CartridgeAssemblyGate>> GATES = new HashMap<>();
    private static boolean registered;
    private ServerCartridgeAssemblyService() {}
    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerCartridgeAssemblyService::logout);
        NeoForge.EVENT_BUS.addListener(ServerCartridgeAssemblyService::stopped);
    }
    private static boolean active(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.isShiftKeyDown()
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }
    private static boolean admit(ServerPlayer player, UUID request) {
        return GATES.computeIfAbsent(player.getServer(), key -> new HashMap<>())
                .computeIfAbsent(player.getUUID(), key -> new CartridgeAssemblyGate())
                .admit(request, player.getServer().getTickCount());
    }
    public static void dismantle(ServerPlayer player, CartridgeAssemblyBinding binding) {
        if (!active(player) || !admit(player, binding.requestId())) return;
        ItemStack held = player.getMainHandItem();
        try {
            if (!FcCartridgeData.isCartridge(held) || held.getCount() != 1 || player.getInventory().selected != binding.slot()) return;
            if (!FcCartridgeData.supportsAssembly(held)) {
                message(player, "卡带含额外名称、描述或其他自定义数据，当前不能无损拆卸；原物品已保留。"); return;
            }
            if (!binding.permits(player.getInventory().selected, FcCartridgeData.id(held),
                    FcCartridgeData.assemblyRevision(held), held.getCount(), FcCartridgeData.isCartridge(held),
                    player.isShiftKeyDown(), player.isAlive() && !player.isSpectator(),
                    player.containerMenu == player.inventoryMenu)) return;
            // No mutations (including random appearance persistence) occur until the empty slot is known.
            if (player.getInventory().items.stream().noneMatch(ItemStack::isEmpty)) {
                message(player, "背包需要一个空格才能拆卡；卡带未消耗。"); return;
            }
            CartridgeParts.Split parts = CartridgeParts.split(FcCartridgeData.whole(held),
                    player.getRandom().nextInt(CartridgeParts.VARIANT_COUNT), UUID.randomUUID());
            ItemStack board = new ItemStack(ModItems.FC_CARTRIDGE_BOARD.get());
            ItemStack shell = new ItemStack(ModItems.FC_CARTRIDGE_SHELL.get());
            FcCartridgeData.writeBoard(board, parts.board()); FcCartridgeData.writeShell(shell, parts.shell());
            if (!CartridgeAssemblyInventory.split(player.getInventory().items, binding.slot(), held, board, shell, ItemStack::isEmpty)) return;
            changed(player);
            message(player, "卡壳已拆开：电路板留在主手，原封面外壳放入背包。");
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            message(player, "卡带数据无效，未拆卸或消耗物品。");
        }
    }
    public static boolean combine(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !active(player)) return false;
        ItemStack board = player.getMainHandItem(), shell = player.getOffhandItem();
        if (!FcCartridgeData.isBoard(board) || !FcCartridgeData.isShell(shell)
                || board.getCount() != 1 || shell.getCount() != 1 || !admit(player, UUID.randomUUID())) return false;
        try {
            if (!FcCartridgeData.supportsAssembly(board) || !FcCartridgeData.supportsAssembly(shell)) {
                message(player, "部件含额外名称、描述或其他自定义数据，当前不能无损合并；两件物品已保留。"); return false;
            }
            CartridgeParts.Whole parts = CartridgeParts.combine(FcCartridgeData.board(board), FcCartridgeData.shell(shell));
            ItemStack whole = new ItemStack(ModItems.FC_CARTRIDGE.get());
            FcCartridgeData.writeWhole(whole, parts);
            if (!CartridgeAssemblyInventory.combine(player.getInventory().items, player.getInventory().offhand,
                    player.getInventory().selected, board, shell, whole, ItemStack.EMPTY, ItemStack::isEmpty)) return false;
            changed(player); message(player, "卡壳已合回：游戏来自电路板，封面来自副手外壳。"); return true;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            message(player, "部件数据无效，未合并或消耗物品。"); return false;
        }
    }
    private static void changed(ServerPlayer player) {
        // Replacing the stack invalidates any concurrent OP editor binding before asynchronous IO can commit.
        ServerCartridgeService.cancelForAssembly(player);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
    }
    private static void message(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Map<UUID, CartridgeAssemblyGate> players = GATES.get(player.getServer());
            if (players != null) players.remove(player.getUUID());
        }
    }
    private static void stopped(ServerStoppedEvent event) { GATES.remove(event.getServer()); }
}
