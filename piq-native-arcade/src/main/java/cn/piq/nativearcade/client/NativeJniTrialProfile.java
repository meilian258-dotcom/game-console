// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.nativearcade.NativeNetplayProfile;
import cn.piq.retro.libretro.LibretroJniRuntime;
import cn.piq.retro.libretro.LibretroProfile;
import java.util.List;
import java.util.Map;

/**
 * Developer-only FBNeo JNI probe. NOT registered with a cabinet, menu, server or Netplay factory.
 * This declaration makes no multiplayer, OP maintenance or paid-coin authorization claim.
 * All creation/load/frame/close calls belong to one dedicated native owner worker, never the
 * Minecraft game/render thread. Native faults can terminate Minecraft; the runtime never falls
 * back to MAME/another transport and never kills a blocked native thread.
 *
 * <p>Use {@link LibretroJniRuntime#loadFiles(String, Map, java.nio.file.Path)} with the original
 * ZIP basename and only its required, validated BIOS ZIPs. Do not stage cheat/IPS/config files.
 * Pass {@code null} for disposable probe storage, or an explicitly separate JNI trial save
 * directory; never use ordinary arcade/Netplay saves. This class does not import/export saves.
 * Options are checked against the pinned artifact's actual KOV declaration, not just upstream
 * source. Other ROMs may offer different options and must pass their own isolated probe; an
 * unsupported option is a hard failure, not an instruction to silently weaken this profile.
 */
public final class NativeJniTrialProfile {
    private NativeJniTrialProfile() {}

    public static LibretroProfile profile() {
        return new LibretroProfile("FinalBurn Neo", "zip", true, List.of(1, 1, 1, 1), false,
                Map.of("fbneo-samplerate", "48000",
                        "fbneo-fixed-frameskip", "0",
                        "fbneo-force-60hz", "disabled",
                        "fbneo-allow-patched-romsets", "disabled",
                        // Pinned a251c76 retro_common.cpp declares "None", not "disabled".
                        // Do not copy Netplay's OP-gated Hold Start + L + R setting here.
                        "fbneo-diagnostic-input", "None"),
                Map.of("windows-x64", new LibretroProfile.Artifact(
                        NativeNetplayProfile.CORE_RESOURCE, NativeNetplayProfile.CORE_SHA)));
    }

    /** Construct on the worker that will also load, run and close this explicitly selected trial. */
    public static LibretroJniRuntime createOnOwnerThread() {
        return new LibretroJniRuntime(profile(), NativeJniTrialProfile.class);
    }
}
