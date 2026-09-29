package cn.piq.fcarcade.home;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** One server broadcast after a successful physical transaction, never from NBT/render/tick. */
public final class HomeInteractionSounds {
    public enum Action {
        POWER_ON, POWER_OFF, RESET, VOLUME, CARTRIDGE_INSERT, CARTRIDGE_EJECT,
        CABLE_CONNECT, CABLE_DISCONNECT, CONTROLLER_TAKE, CONTROLLER_RETURN
    }

    private HomeInteractionSounds() {}

    public static void play(ServerLevel level, BlockPos pos, Action action) {
        if (level == null || pos == null || action == null || !level.getServer().isSameThread()
                || !level.hasChunkAt(pos)) return;
        // Vanilla samples need no extra runtime/assets and respect the player's BLOCKS slider.
        var sound = switch (action) {
            case CARTRIDGE_INSERT, CONTROLLER_RETURN, CABLE_CONNECT -> SoundEvents.STONE_BUTTON_CLICK_ON;
            case CARTRIDGE_EJECT, CONTROLLER_TAKE, CABLE_DISCONNECT -> SoundEvents.STONE_BUTTON_CLICK_OFF;
            default -> SoundEvents.LEVER_CLICK;
        };
        float pitch = switch (action) {
            case POWER_ON -> 1.15f;
            case POWER_OFF -> .9f;
            case RESET -> 1.35f;
            case VOLUME -> 1.65f;
            case CARTRIDGE_INSERT, CONTROLLER_RETURN -> .85f;
            case CARTRIDGE_EJECT, CONTROLLER_TAKE -> 1.05f;
            case CABLE_CONNECT -> 1.35f;
            case CABLE_DISCONNECT -> 1.2f;
        };
        try {
            level.playSound(null, pos, sound, SoundSource.BLOCKS, action == Action.VOLUME ? .12f : .24f, pitch);
        } catch (RuntimeException failure) {
            // A sound/protection extension must not turn an already committed inventory action into a retry.
            cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Home interaction sound failed at {}", pos, failure);
        }
    }
}
