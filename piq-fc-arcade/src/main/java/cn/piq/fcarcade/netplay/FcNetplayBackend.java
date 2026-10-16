// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import java.nio.charset.StandardCharsets;

/**
 * FC adapter for the shared launch contract. No native load, file IO, or client classes.
 * The legacy JNI FC protocol/save domain remains special; it is not the generic JNI adapter.
 * A server selects a declaration, never a library path, and cannot request a fallback backend.
 */
public final class FcNetplayBackend {
    public static final String OFFICIAL_PAD = "fceumm-v1";
    private static final String PLACEHOLDER_CONTENT = "0".repeat(64);
    private static final NetplayLaunchSpec JNI_PAD = jni(false);
    private static final NetplayLaunchSpec JNI_GUN = jni(true);
    private static final NetplayLaunchSpec RA_PAD = retroarch(false);
    private static final NetplayLaunchSpec RA_GUN = retroarch(true);
    private static final NetplayLaunchSpec RA_OFFICIAL_PAD = new NetplayVariant("fc-retroarch-fceumm-v1",
            officialLabel(), FcRetroarchPad.profile(), true).launch();

    private FcNetplayBackend() { }

    public static NetplayLaunchSpec launch(boolean gun, boolean jni) {
        return jni ? (gun ? JNI_GUN : JNI_PAD) : (gun ? RA_GUN : RA_PAD);
    }

    /** Empty is the historical selection. Unknown NBT never silently selects a different core. */
    public static NetplayLaunchSpec launch(boolean gun, boolean jni, String padVariant) {
        validateSelection(gun, jni, padVariant);
        return padVariant.isEmpty() ? launch(gun, jni) : RA_OFFICIAL_PAD;
    }

    public static void validateSelection(boolean gun, boolean jni, String padVariant) {
        if (padVariant == null || !padVariant.isEmpty() && (!OFFICIAL_PAD.equals(padVariant) || gun || jni))
            throw new IllegalArgumentException("此 FC 核心选择不可用；新增 RetroArch 仅支持双手柄，请关机后核对设备设置，旧进度保持不变");
    }

    public static String officialLabel() { return "RetroArch 双手柄（FCEUmm）"; }

    public static String label(boolean jni, NetplayLaunchSpec launch) {
        return RA_OFFICIAL_PAD.equals(launch) ? officialLabel() : label(jni);
    }

    /** Only resolve a complete trusted contract, never a packet-supplied core path. */
    public static NetplayProfile retroarchProfile(boolean gun, NetplayLaunchSpec launch) {
        verify(gun, false, launch);
        return RA_OFFICIAL_PAD.equals(launch) ? FcRetroarchPad.profile()
                : gun ? NetplayProfile.fcZapper() : NetplayProfile.fc();
    }

    public static String label(boolean jni) {
        return jni ? "JNI Netplay（进程内）" : "RetroArch Netplay（独立进程）";
    }

    /** Validate before ROM access, persistence binding, or native startup. */
    public static void verify(boolean gun, boolean jni, NetplayLaunchSpec grant) {
        if (!launch(gun, jni).equals(grant) && (gun || jni || !RA_OFFICIAL_PAD.equals(grant)))
            throw new IllegalArgumentException("FC 联机后端、核心或协议不匹配，请安装同批配套；不会自动切换后端或转换存档");
    }

    private static NetplayLaunchSpec retroarch(boolean gun) {
        return new NetplayVariant(gun ? "fc-retroarch-zapper-v1" : "fc-retroarch-pad-v1",
                label(false), gun ? NetplayProfile.fcZapper() : NetplayProfile.fc(), true).launch();
    }

    private static NetplayLaunchSpec jni(boolean gun) {
        // Include the exact legacy save profile (core/options/devices) and handshake ABI.
        // Do not build a generic NetplayProfile: that changes the FC handshake/save identity
        // and cannot represent the special Mesen gun timeline.
        var profile = JniNetplaySession.profile(gun);
        var artifact = profile.cores().get("windows-x64");
        String descriptor = "PIQ-FC-JNI-launch-v1\n" + gun + "\n"
                + JniNetplayCodec.MAGIC + ":" + JniNetplayCodec.VERSION + "\n"
                + NativeLibretroBridge.ABI + "\n" + artifact.resource() + "\n"
                + artifact.sha256() + "\n" + artifact.maxBytes() + "\n"
                + FcNetplaySaves.jniIdentity(gun, PLACEHOLDER_CONTENT).profile();
        return new NetplayLaunchSpec(gun ? "fc-jni-zapper-v1" : "fc-jni-pad-v2",
                NetplaySaveState.hash(descriptor.getBytes(StandardCharsets.UTF_8)), true);
    }
}
