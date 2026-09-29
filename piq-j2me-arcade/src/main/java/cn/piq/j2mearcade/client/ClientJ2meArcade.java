package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;
import cn.piq.j2mearcade.core.J2meGameDescriptor;
import cn.piq.j2mearcade.core.J2meGameLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Client-only entry point kept out of common block construction. */
public final class ClientJ2meArcade {
    private ClientJ2meArcade() {
    }

    public static void register(IEventBus ignoredModBus) {
        NeoForge.EVENT_BUS.addListener(ClientJ2meArcade::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(ClientJ2meArcade::onRenderLevel);
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        ModernUiBlurCompat.prepareScreens();
        Path gameDirectory = minecraft.gameDirectory.toPath().resolve("j2me-games");
        try {
            List<J2meGameDescriptor> games = J2meGameLibrary.scan(gameDirectory);
            if (games.size() == 1) {
                minecraft.setScreen(new J2meGameScreen(games.getFirst()));
            } else {
                minecraft.setScreen(new J2meLibraryScreen(gameDirectory, games));
            }
        } catch (IOException error) {
            PiqJ2meArcadeMod.LOGGER.error("[PIQ J2ME] Failed to scan game directory", error);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        "message.piq_j2me_arcade.scan_failed", safeMessage(error)), false);
            }
        }
    }

    public static void openSelector(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.hasPermissions(2)) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        "message.piq_j2me_arcade.op_only"), false);
            }
            return;
        }
        ModernUiBlurCompat.prepareScreens();
        Path gameDirectory = minecraft.gameDirectory.toPath().resolve("j2me-games");
        try {
            List<J2meGameDescriptor> games = J2meGameLibrary.scan(gameDirectory);
            minecraft.setScreen(new J2meLibraryScreen(gameDirectory, games, pos));
        } catch (IOException error) {
            reportScanFailure(minecraft, error);
        }
    }

    public static void openMachine(BlockPos pos, String selectedJar) {
        Minecraft minecraft = Minecraft.getInstance();
        if (selectedJar == null || selectedJar.isBlank()) {
            if (minecraft.player != null && minecraft.player.hasPermissions(2)) {
                openSelector(pos);
            } else if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        "message.piq_j2me_arcade.unconfigured"), false);
            }
            return;
        }
        Path gameDirectory = minecraft.gameDirectory.toPath().resolve("j2me-games");
        try {
            J2meGameDescriptor game = J2meGameLibrary.scan(gameDirectory).stream()
                    .filter(candidate -> candidate.jar().getFileName().toString()
                            .equals(selectedJar))
                    .findFirst()
                    .orElse(null);
            if (game == null) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.piq_j2me_arcade.game_missing", selectedJar), false);
                }
                return;
            }
            startMachine(pos, game);
        } catch (IOException error) {
            reportScanFailure(minecraft, error);
        }
    }

    static void startMachine(BlockPos pos, J2meGameDescriptor game) {
        Minecraft minecraft = Minecraft.getInstance();
        ModernUiBlurCompat.prepareScreens();
        try {
            ClientJ2meMachineRuntime.INSTANCE.start(pos, game);
            minecraft.setScreen(new J2meMachineControlScreen(
                    ClientJ2meMachineRuntime.INSTANCE));
        } catch (Exception error) {
            PiqJ2meArcadeMod.LOGGER.error("[PIQ J2ME] Failed to start {}",
                    game.jar(), error);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        "screen.piq_j2me_arcade.load_failed", safeMessage(error)), false);
            }
        }
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientJ2meMachineRuntime.INSTANCE.update();
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        J2meMachineScreenRenderer.render(event, ClientJ2meMachineRuntime.INSTANCE);
    }

    private static void reportScanFailure(Minecraft minecraft, IOException error) {
        PiqJ2meArcadeMod.LOGGER.error("[PIQ J2ME] Failed to scan game directory", error);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "message.piq_j2me_arcade.scan_failed", safeMessage(error)), false);
        }
    }

    static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
