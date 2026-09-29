// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.util.Locale;
import java.util.Objects;

/** Trusted adapters choose a backend; defaults never migrate saves or retry a failed core. */
public final class LibretroRuntimes {
    public enum Backend { PROCESS, JNI_TRIAL }
    /** Only fully adapted client entry points may opt into this default. Server callers stay explicit. */
    public static Backend defaultBackend(boolean adapted) {
        return defaultBackend(adapted,System.getProperty("os.name",""),System.getProperty("os.arch",""));
    }
    static Backend defaultBackend(boolean adapted,String os,String arch) {
        return adapted && os.toLowerCase(Locale.ROOT).startsWith("windows")
                && (arch.equals("amd64") || arch.equals("x86_64")) ? Backend.JNI_TRIAL : Backend.PROCESS;
    }
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
