package cn.piq.replaycompat.capture;

import java.util.Set;

/** Exact device protocol allowlist: unrelated mods, vanilla and NeoForge stay untouched. */
public final class ReplayGuardPolicy {
    private static final Set<String> DEVICE_NAMESPACES = Set.of("piq_fc_arcade", "piq_sfc_home",
            "piq_sfc_arcade", "piq_native_arcade", "piq_gba", "piq_md_home", "piq_j2me_arcade",
            "piq_computer", "piq_flash_box", "piq_pvz");
    private ReplayGuardPolicy() {}
    public static boolean blocks(boolean actualReplay, String namespace) {
        return actualReplay && namespace != null && DEVICE_NAMESPACES.contains(namespace);
    }
}
