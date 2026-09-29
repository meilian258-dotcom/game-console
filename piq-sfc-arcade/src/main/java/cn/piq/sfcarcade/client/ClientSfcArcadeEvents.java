// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.core.SfcButton;
import cn.piq.sfcarcade.net.SfcNetwork;
import cn.piq.sfcarcade.world.SfcArcadeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.nio.file.Path;
import java.util.UUID;

public final class ClientSfcArcadeEvents {
    private static LocalSfcSession session;
    private static String shownError;
    private static BlockPos pendingSession;
    private static UUID activeSessionId = SfcNetwork.SessionStatePayload.INACTIVE_SESSION;

    private ClientSfcArcadeEvents() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ClientSfcArcadeEvents::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientSfcArcadeEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(ClientSfcArcadeEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientSfcArcadeEvents::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ClientSfcArcadeEvents::onKeyInput);
        NeoForge.EVENT_BUS.addListener(ClientSfcArcadeEvents::registerClientCommands);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND
                || event.getEntity() != minecraft.player
                || !(event.getLevel().getBlockState(event.getPos()).getBlock()
                instanceof SfcArcadeBlock)) {
            return;
        }

        BlockPos pos = event.getPos();
        if (event.getEntity().isShiftKeyDown()) {
            SfcNetwork.requestLibrary(pos);
            return;
        }
        if (session != null && session.matchesLevel(event.getLevel())
                && session.blockPos().equals(pos)) {
            SfcNetwork.requestSession(pos, true);
            return;
        }
        pendingSession = pos.immutable();
        SfcNetwork.requestSession(pos, false);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientSfcRomTransfers.update();
        SfcKeyMappings.syncOtherMappings();
        if (session == null) return;
        if (minecraft.level == null || minecraft.player == null
                || !session.matchesLevel(minecraft.level)
                || !(minecraft.level.getBlockState(session.blockPos()).getBlock()
                instanceof SfcArcadeBlock)
                || minecraft.player.distanceToSqr(
                session.blockPos().getX() + 0.5,
                session.blockPos().getY() + 0.5,
                session.blockPos().getZ() + 0.5) > 64.0 * 64.0) {
            BlockPos previous = session.blockPos();
            stop(null);
            if (minecraft.getConnection() != null) {
                SfcNetwork.requestSession(previous, true);
            }
            return;
        }

        session.setInputMask(minecraft.screen == null ? readInput(minecraft) : 0);
        double distance = Math.sqrt(minecraft.player.distanceToSqr(
                session.blockPos().getX() + 0.5,
                session.blockPos().getY() + 0.5,
                session.blockPos().getZ() + 0.5));
        float attenuation = (float) Math.max(0.0, Math.min(1.0,
                1.0 - Math.max(0.0, distance - 2.0) / 22.0));
        float minecraftVolume = minecraft.options.getSoundSourceVolume(SoundSource.MASTER)
                * minecraft.options.getSoundSourceVolume(SoundSource.BLOCKS);
        session.setAudioGain(minecraftVolume * attenuation);
        String error = session.error();
        if (error != null && !error.equals(shownError)) {
            shownError = error;
            minecraft.gui.setOverlayMessage(Component.translatable(
                    "message.piq_sfc_arcade.core_error", error), false);
        }
        SfcKeyMappings.syncOtherMappings();
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (session != null) SfcArcadeScreenRenderer.render(event, session);
    }

    private static int readInput(Minecraft minecraft) {
        int mask = 0;
        mask = add(mask, SfcButton.UP, SfcKeyMappings.UP.isDown());
        mask = add(mask, SfcButton.DOWN, SfcKeyMappings.DOWN.isDown());
        mask = add(mask, SfcButton.LEFT, SfcKeyMappings.LEFT.isDown());
        mask = add(mask, SfcButton.RIGHT, SfcKeyMappings.RIGHT.isDown());
        mask = add(mask, SfcButton.B,
                SfcKeyMappings.B.isDown() || SfcKeyMappings.B_ALT.isDown());
        mask = add(mask, SfcButton.A,
                SfcKeyMappings.A.isDown() || SfcKeyMappings.A_ALT.isDown());
        mask = add(mask, SfcButton.Y,
                SfcKeyMappings.Y.isDown() || SfcKeyMappings.Y_ALT.isDown());
        mask = add(mask, SfcButton.X,
                SfcKeyMappings.X.isDown() || SfcKeyMappings.X_ALT.isDown());
        mask = add(mask, SfcButton.L, SfcKeyMappings.L.isDown());
        mask = add(mask, SfcButton.R, SfcKeyMappings.R.isDown());
        mask = add(mask, SfcButton.START, SfcKeyMappings.START.isDown());
        mask = add(mask, SfcButton.SELECT,
                SfcKeyMappings.SELECT.isDown() || SfcKeyMappings.SELECT_ALT.isDown());
        return mask;
    }

    private static int add(int mask, SfcButton button, boolean pressed) {
        return pressed ? mask | button.mask() : mask;
    }

    private static void start(BlockPos pos,
                              net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                              Path rom,
                              UUID sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (session != null) session.close();
        session = new LocalSfcSession(pos, dimension, rom);
        activeSessionId = sessionId;
        pendingSession = null;
        shownError = null;
        minecraft.gui.setOverlayMessage(Component.translatable(
                "message.piq_sfc_arcade.started", rom.getFileName().toString()), false);
        SfcKeyMappings.syncOtherMappings();
    }

    private static void stop(Component message) {
        if (session != null) session.close();
        session = null;
        activeSessionId = SfcNetwork.SessionStatePayload.INACTIVE_SESSION;
        pendingSession = null;
        shownError = null;
        SfcKeyMappings.restoreAll();
        Minecraft minecraft = Minecraft.getInstance();
        if (message != null && minecraft.gui != null) {
            minecraft.gui.setOverlayMessage(message, false);
        }
    }

    public static void acceptSession(SfcNetwork.SessionStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!payload.active()) {
            if (session != null && session.blockPos().equals(payload.pos())) {
                stop(Component.translatable("message.piq_sfc_arcade.stopped"));
            } else if (pendingSession != null && pendingSession.equals(payload.pos())) {
                pendingSession = null;
            }
            return;
        }
        if (!payload.owner()) {
            if (pendingSession != null && pendingSession.equals(payload.pos())) {
                pendingSession = null;
            }
            if (minecraft.gui != null) {
                minecraft.gui.setOverlayMessage(Component.translatable(
                        "message.piq_sfc_arcade.occupied", payload.playerName()), false);
            }
            return;
        }
        if (session != null
                && session.blockPos().equals(payload.pos())
                && activeSessionId.equals(payload.sessionId())) {
            pendingSession = null;
            return;
        }
        if (minecraft.level == null) {
            SfcNetwork.requestSession(payload.pos(), true);
            return;
        }
        pendingSession = payload.pos().immutable();
        ClientSfcRomTransfers.ensureLocal(payload.romHash(), rom -> {
            Minecraft current = Minecraft.getInstance();
            if (current.level == null
                    || pendingSession == null
                    || !pendingSession.equals(payload.pos())) {
                SfcNetwork.requestSession(payload.pos(), true);
                return;
            }
            start(payload.pos(), current.level.dimension(), rom, payload.sessionId());
        });
    }

    static boolean isControlling() {
        return session != null;
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        SfcKeyMappings.register(event);
    }

    private static void onKeyInput(InputEvent.Key event) {
        SfcKeyMappings.syncOtherMappings();
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("sfc-keys")
                        .executes(context -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.setScreen(new KeyBindsScreen(
                                    minecraft.screen,
                                    minecraft.options));
                            return 1;
                        }));
    }
}
