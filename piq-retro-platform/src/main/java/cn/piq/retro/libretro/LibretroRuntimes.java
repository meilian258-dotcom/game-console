// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.util.Locale;
import java.util.Objects;

/** Explicit local choice. No global preference, automatic migration or implicit server selection. */
public final class LibretroRuntimes {
    public enum Backend { PROCESS, JNI_TRIAL }
    public static LibretroRuntime create(LibretroProfile profile, Class<?> resourceOwner, Backend backend) {
        Objects.requireNonNull(backend);
        return backend == Backend.PROCESS ? new LibretroProcess(profile, resourceOwner)
                : new LibretroJniRuntime(profile, resourceOwner);
    }
    public static String jniUnavailableReason() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))
                ? "" : "通用 JNI 试验目前仅支持 Windows x64，请使用独立进程";
    }
    public static boolean isJniBusy() { return LibretroJniRuntime.isBusy(); }
    private LibretroRuntimes() { }
}
