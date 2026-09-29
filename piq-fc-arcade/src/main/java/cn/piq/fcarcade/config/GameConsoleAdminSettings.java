package cn.piq.fcarcade.config;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-scoped defaults are copied at new placement only, never read as a live mode override. */
public final class GameConsoleAdminSettings extends SavedData {
    private int mode = -1;
    private int range = GameConsoleAdminPolicy.DEFAULT_RANGE;
    private GameConsoleAdminSettings() {}
    private static GameConsoleAdminSettings data(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Server thread required");
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(GameConsoleAdminSettings::new, GameConsoleAdminSettings::load), "piq_console_admin_defaults");
    }
    public static int defaultMode(MinecraftServer server) { return data(server).mode; }
    public static int defaultRange(MinecraftServer server) { return data(server).range; }
    /** Package-private writes are called only after the OP2 command boundary. */
    static void setDefaults(MinecraftServer server, int mode, int range) {
        if (!GameConsoleAdminPolicy.validMode(mode) || !GameConsoleAdminPolicy.validRange(range)) throw new IllegalArgumentException("Invalid console defaults");
        var data = data(server); data.mode = mode; data.range = range; data.setDirty();
    }
    public static int watchRange(ServerLevel level, BlockPos source) { return watchRange(level, source, GameConsoleAdminPolicy.DEFAULT_RANGE); }
    public static int watchRange(ServerLevel level, BlockPos source, int legacyRange) {
        int fallback = GameConsoleAdminPolicy.persistedRange(legacyRange, GameConsoleAdminPolicy.DEFAULT_RANGE);
        if (level == null || source == null || !level.hasChunkAt(source)) return fallback;
        var block = level.getBlockEntity(source);
        if (block instanceof HomeConsoleBlockEntity home) return home.observationRange(fallback);
        if (block instanceof ExternalHomeConsoleBlockEntity external) return external.observationRange(fallback);
        if (block instanceof LegacyFcArcadeBlockEntity) return cn.piq.fcarcade.cabinet.CabinetServerSettings.rules(level.getServer()).range();
        return fallback;
    }
    public static int exitRange(ServerLevel level, BlockPos source) { return watchRange(level, source)+GameConsoleAdminPolicy.EXIT_MARGIN; }
    static GameConsoleAdminSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new GameConsoleAdminSettings();
        if (tag.contains("Mode", 3) && GameConsoleAdminPolicy.validMode(tag.getInt("Mode"))) result.mode = tag.getInt("Mode");
        result.range = GameConsoleAdminPolicy.persistedRange(tag.getInt("Range"), GameConsoleAdminPolicy.DEFAULT_RANGE);
        return result;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { tag.putInt("Mode", mode); tag.putInt("Range", range); return tag; }
}
