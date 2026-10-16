// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.netplay;

import java.util.Map;

/** An explicit additional FC pad route. Never replaces the legacy Mesen or JNI declarations. */
public final class FcRetroarchPad {
    public static final String SHA256 = "a04f7fa28bdd46f602bb62750ab9b5e360d8e8d8ece97de8ad3ef1b669257ffd";
    public static final String RESOURCE = "/core/fc-retroarch/windows-x64/fceumm_libretro.dll";
    private static final NetplayProfile PROFILE = new NetplayProfile(NetplayProcess.class,
            RESOURCE, SHA256, "content.nes", Map.ofEntries(
                    Map.entry("fceumm_region", "NTSC"), Map.entry("fceumm_sndrate_hint", "48KHz"),
                    Map.entry("fceumm_sndquality", "Low"), Map.entry("fceumm_sndlowpass", "disabled"),
                    Map.entry("fceumm_palette", "default"), Map.entry("fceumm_overscan_h_left", "0"),
                    Map.entry("fceumm_overscan_h_right", "0"), Map.entry("fceumm_overscan_v_top", "0"),
                    Map.entry("fceumm_overscan_v_bottom", "0"), Map.entry("fceumm_nospritelimit", "disabled"),
                    Map.entry("fceumm_overclocking", "disabled"), Map.entry("fceumm_turbo_enable", "None"),
                    Map.entry("fceumm_game_genie", "disabled")), 257, 48000, 16 * 1024 * 1024);
    private FcRetroarchPad() { }
    public static NetplayProfile profile() { return PROFILE; }
}
