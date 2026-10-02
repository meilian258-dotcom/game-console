package cn.piq.fcarcade.home;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Read-only per-machine preferences, never an authority to join or power a game. */
public final class HomePresentationSettings {
    /** Unadapted home systems retain a disabled toggle. */
    public static boolean occupancySupported(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return false;
        var endpoint = HomeHardware.loadedEndpoint(level, pos);
        if (endpoint instanceof HomeTvBlockEntity)
            endpoint = HomeHardware.connectedEndpoint(level, endpoint.getBlockPos());
        if (endpoint instanceof HomeConsoleBlockEntity) return true;
        if (endpoint instanceof ExternalHomeConsoleBlockEntity external) {
            var hooks = HomeSystems.applianceHooks(external.systemId());
            try { return hooks != null && hooks.occupancyDisplaySupported(); }
            catch (RuntimeException | LinkageError unavailable) { return false; }
        }
        return false;
    }
    private HomePresentationSettings() {}
    public static boolean occupancyVisible(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return true;
        var endpoint = HomeHardware.loadedEndpoint(level, pos);
        if (endpoint instanceof HomeTvBlockEntity) endpoint = HomeHardware.connectedEndpoint(level, endpoint.getBlockPos());
        return endpoint == null || endpoint.occupancyVisible();
    }
}
