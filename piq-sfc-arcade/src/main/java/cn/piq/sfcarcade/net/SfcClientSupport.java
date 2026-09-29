// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.net;

import cn.piq.sfcarcade.client.ClientSfcArcadeEvents;
import cn.piq.sfcarcade.client.ClientSfcRomLibrary;
import cn.piq.sfcarcade.client.ClientSfcRomTransfers;
import cn.piq.sfcarcade.client.SfcRomLibraryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;

import java.io.IOException;

/**
 * Client-only linkage boundary. Common networking may reference these methods without making a
 * dedicated server verify Minecraft client classes while the mod is being constructed.
 */
public final class SfcClientSupport {
    private SfcClientSupport() {
    }

    public static void registerEvents(IEventBus modBus) {
        ClientSfcArcadeEvents.register(modBus);
    }

    static void openLibrary(SfcNetwork.LibraryPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            minecraft.setScreen(new SfcRomLibraryScreen(
                    payload,
                    ClientSfcRomLibrary.list()));
        } catch (IOException | IllegalArgumentException exception) {
            if (minecraft.gui != null) {
                String reason = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                minecraft.gui.setOverlayMessage(Component.translatable(
                        "message.piq_sfc_arcade.library_error", reason), false);
            }
        }
    }

    static void startDownload(SfcNetwork.DownloadStartPayload payload) {
        ClientSfcRomTransfers.start(payload);
    }

    static void acceptDownload(SfcNetwork.DownloadChunkPayload payload) {
        ClientSfcRomTransfers.accept(payload);
    }

    static void acceptSession(SfcNetwork.SessionStatePayload payload) {
        ClientSfcArcadeEvents.acceptSession(payload);
    }
}
