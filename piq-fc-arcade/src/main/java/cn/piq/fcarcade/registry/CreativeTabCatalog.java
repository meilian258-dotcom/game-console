package cn.piq.fcarcade.registry;

import java.util.ArrayList;
import java.util.List;

/** Stable display order, shared by the real tab callback and plain JVM tests. */
public final class CreativeTabCatalog {
    private static final List<String> BASE = List.of(
            "famicom_console", "retro_tv", "fc_cartridge", "av_cable",
            "subor_console", "panel_tv_2", "panel_tv_3", "gray_crt_tv", "red_crt_tv", "dual_cabinet", "arcade_coin", "fc_cartridge_board", "fc_cartridge_shell",
            "legacy_fc_arcade", "portrait_cabinet", "cartridge_computer", "tv_remote", "debug_screwdriver", "admin_terminal", "fc_zapper", "zapper_stand", "zapper_stand_cable");
    // Retired display entries stay registered for existing worlds and /give:
    // fc_arcade, stream_fc_arcade, deluxe_fc_arcade,
    // deluxe_stream_fc_arcade, leaderboard_panel.
    private static final List<String> WATERFRAMES = List.of(
            "waterframes_fc_arcade", "waterframes_tv_fc_arcade",
            "waterframes_tv_box_fc_arcade", "waterframes_panel_fc_arcade");

    private CreativeTabCatalog() {}

    public static List<String> itemPaths(boolean waterFramesLoaded) {
        if (!waterFramesLoaded) return BASE;
        var result = new ArrayList<>(BASE);
        result.addAll(WATERFRAMES);
        return List.copyOf(result);
    }
}
