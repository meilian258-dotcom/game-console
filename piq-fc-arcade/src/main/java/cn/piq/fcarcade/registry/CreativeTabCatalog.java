// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.registry;

import java.util.ArrayList;
import java.util.List;

/** Stable display order, shared by the real tab callback and plain JVM tests. */
public final class CreativeTabCatalog {
    private static final List<String> BASE = List.of(
            "famicom_console", "subor_console",
            "legacy_fc_arcade", "portrait_cabinet", "dual_cabinet", "arcade_coin",
            "retro_tv", "gray_crt_tv", "red_crt_tv", "panel_tv_2", "panel_tv_3",
            "fc_cartridge", "fc_cartridge_board", "fc_cartridge_shell", "cartridge_computer",
            "av_cable", "fc_zapper", "zapper_stand", "zapper_stand_cable",
            "tv_remote", "debug_screwdriver", "admin_terminal");
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

    /** Optional displays are emitted after furniture, separately from the ordinary catalog. */
    public static List<String> waterFramesItemPaths() {
        return WATERFRAMES;
    }
}
