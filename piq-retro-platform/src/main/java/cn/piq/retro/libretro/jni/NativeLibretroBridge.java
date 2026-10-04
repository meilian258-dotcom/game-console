// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;

import cn.piq.retro.libretro.LibretroProcess;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Implementation ABI, not an addon entry point. Use LibretroJniRuntime with a pinned profile. */
public final class NativeLibretroBridge {
    public static final int ABI = 2;
    private static volatile boolean loaded;
    private static volatile String loadFailure;
    // System.load keeps this bridge for the JVM lifetime. Never delete while a native call may use it.
    private static RuntimeWorkspace libraryWorkspace;
    private static AutoCloseable libraryPin;
    public static synchronized void load() throws IOException {
        if (loaded) return;
        if (loadFailure != null) throw new IOException(loadFailure);
        Properties manifest = new Properties();
        try (var in = resource("/core/libretro-jni/runtime.properties")) { manifest.load(in); }
        if (!Integer.toString(ABI).equals(manifest.getProperty("abi"))) throw new IOException("Bundled generic JNI ABI mismatch");
        String sha = manifest.getProperty("windows-x64.sha256", "");
        if (!sha.matches("[A-Fa-f0-9]{64}")) throw new IOException("Missing pinned generic JNI manifest");
        RuntimeWorkspace workspace = RuntimeWorkspace.create("libretro", 16L * 1024 * 1024);
        AutoCloseable pin = null;
        boolean nativeLoaded = false;
        try {
            Path dll = workspace.directory().resolve("piq-libretro-jni.dll");
            extract(LibretroProcess.class, "/core/libretro-jni/windows-x64/piq-libretro-jni.dll", sha, dll, 16L * 1024 * 1024);
            pin = workspace.pinNative();
            System.load(dll.toString()); nativeLoaded = true;
            libraryWorkspace = workspace; libraryPin = pin;
            try {
                if (abiVersion() != ABI) throw new IOException("Generic JNI ABI mismatch");
            } catch (IOException | LinkageError e) {
                loadFailure = "Generic JNI bridge failed after load; restart required: " + e.getMessage();
                throw e;
            }
            loaded = true;
        } finally {
            if (!nativeLoaded) {
                if (pin != null) try { pin.close(); } catch (Exception ignored) { }
                workspace.close();
            }
        }
    }
    private static InputStream resource(String name) throws IOException {
        InputStream in = LibretroProcess.class.getResourceAsStream(name);
        if (in == null) throw new IOException("Missing bundled resource: " + name);
        return in;
    }
    public static void extract(Class<?> owner, String resource, String expected, Path target, long limit) throws IOException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try (var in = owner.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing bundled core: " + resource);
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; long count = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n; if (count > limit) throw new IOException("Native artifact exceeds budget");
                    sha.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
        }
        if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expected)) throw new IOException("Native artifact checksum mismatch");
    }
    public static native int abiVersion();
    /** Does not load native code during discovery, or wait for a potentially stuck core owner. */
    public static boolean atCapacity() { return freeSlotsIfLoaded() == 0; }
    /** Advisory discovery budget. Never loads a DLL or takes a core owner's lock; reserve() remains authoritative. */
    public static int freeSlotsIfLoaded() { return loadFailure != null ? 0 : loaded ? Math.max(0, Math.min(4, availableSlots())) : 4; }
    public static native int availableSlots();
    /** Reserve before staging/open: cleanup failures always have a known, generation-safe identity. */
    public static native long reserve() throws IOException;
    public static native boolean reservationHeld(long token);
    public static native long openReserved(long token, String core, String content, String system, String save, String expectedName,
                                   boolean fullPath, int[] devices, String[] optionPairs, int features) throws IOException;
    public static native void metadata(long token, int[] metadata, double[] timing) throws IOException;
    public static native String coreVersion(long token) throws IOException;
    public static native int saveCapabilities(long token) throws IOException;
    public static native void step(long token, ByteBuffer rgba, ByteBuffer pcm, int[] input, int[] keys,
                                   int[] metadata, double[] timing) throws IOException;
    public static native byte[] serialize(long token) throws IOException;
    public static native void restore(long token, byte[] state) throws IOException;
    public static native byte[] memory(long token, int id) throws IOException;
    public static native void restoreMemory(long token, byte[] ram, byte[] rtc) throws IOException;
    public static native void reset(long token) throws IOException;
    public static native void close(long token) throws IOException;
    private NativeLibretroBridge() { }
}
