package cn.piq.fcarcade.cabinet;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned opt-in and hard bounds. Registering a core never grants permission to execute it. */
public final class CabinetHostingConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER.comment("Opt in to server-side emulation. Requires installed server runtimes; never downloads executable files.").define("serverHosting", false);
    public static final ModConfigSpec.BooleanValue PLAYER_MEDIA = BUILDER.comment("Allow a player to host cabinet audio/video.").define("allowPlayerHosting", true);
    public static final ModConfigSpec.BooleanValue LOCAL_SYNC = BUILDER.comment("Allow verified cabinet input synchronization.").define("allowLocalSync", true);
    public static final ModConfigSpec.IntValue MAX_ROOMS = BUILDER.comment("Maximum active server-hosted sessions, including closing workers.").defineInRange("maxHostedRooms", 2, 1, 4);
    public static final ModConfigSpec.IntValue BYTES_PER_SECOND = BUILDER.comment("Hosted participant audio/video upload budget in bytes per second, including each recipient copy. Spectators also retain the separate watch budget.").defineInRange("hostedBytesPerSecond", 2 * 1024 * 1024, 256 * 1024, 16 * 1024 * 1024);
    public static final ModConfigSpec.ConfigValue<Integer> VIDEO_FPS = BUILDER.comment("Server-hosted VIDEO delivery cap: 20, 30 or 60. Default 20. Does not change emulation speed or audio; bandwidth/CPU limits still apply. Shared by FC, SFC and cabinets.").define("hostedVideoFps", 20,
            value -> value instanceof Integer fps && cn.piq.fcarcade.server.hosted.HostedVideoPacer.supported(fps));
    public static final ModConfigSpec SPEC = BUILDER.build();
    private CabinetHostingConfig() {}
    public static void register() {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer().registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, SPEC, "piq-sync-server.toml");
    }
    public static boolean enabled() { return SPEC.isLoaded() && ENABLED.get(); }
    public static boolean localAllowed() { return !SPEC.isLoaded() || LOCAL_SYNC.get(); }
    public static boolean playerAllowed() { return !SPEC.isLoaded() || PLAYER_MEDIA.get(); }
    public static int maxRooms() { return SPEC.isLoaded() ? MAX_ROOMS.get() : 2; }
    public static int bytesPerSecond() { return SPEC.isLoaded() ? BYTES_PER_SECOND.get() : 2 * 1024 * 1024; }
    public static int videoFps() { return SPEC.isLoaded() ? VIDEO_FPS.get() : 20; }
    /** Call only on the owning server thread after checking operator authority. */
    public static boolean saveVideoFps(int fps) {
        if (!SPEC.isLoaded() || !cn.piq.fcarcade.server.hosted.HostedVideoPacer.supported(fps)) return false;
        int old = videoFps();
        try { VIDEO_FPS.set(fps); SPEC.save(); return true; }
        catch (RuntimeException failure) {
            VIDEO_FPS.set(old);
            cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Cannot save hosted video delivery cap", failure);
            return false;
        }
    }
}
