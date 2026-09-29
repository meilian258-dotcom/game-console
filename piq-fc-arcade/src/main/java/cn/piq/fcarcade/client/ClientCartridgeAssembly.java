package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.CartridgeNetwork;
import cn.piq.fcarcade.home.FcCartridgeData;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Only translates the gesture; the server owns inventory validation and the transaction. */
public final class ClientCartridgeAssembly {
    private static boolean registered;
    private ClientCartridgeAssembly() {}
    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientCartridgeAssembly::interaction);
    }
    private static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isAttack() || mc.screen != null || mc.player == null || mc.getConnection() == null
                || !mc.player.isAlive() || mc.player.isSpectator() || !mc.player.isShiftKeyDown()
                || !FcCartridgeData.isCartridge(mc.player.getMainHandItem())) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        // One press, one request. Prevent the held attack key from beginning to mine
        // next tick after the complete cartridge is replaced with its bare PCB.
        mc.options.keyAttack.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        CartridgeNetwork.dismantle(mc.player.getMainHandItem(), mc.player.getInventory().selected);
    }
}
