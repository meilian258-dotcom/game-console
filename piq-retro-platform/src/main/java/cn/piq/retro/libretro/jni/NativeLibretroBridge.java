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
        RuntimeDependencyManifest.Bundle manifest;
        try (var in = resource("/core/libretro-jni/runtime.properties")) { manifest = RuntimeDependencyManifest.read(in); }
        RuntimeWorkspace workspace = RuntimeWorkspace.create("libretro", RuntimeDependencyManifest.WORKSPACE_BYTES);
        AutoCloseable pin = null;
        boolean loadAttempted = false;
        try {
            Path dll = workspace.directory().resolve("piq-libretro-jni.dll");
            extract(LibretroProcess.class, "/core/libretro-jni/windows-x64/piq-libretro-jni.dll",
                    manifest.bridgeSha256(), dll, RuntimeDependencyManifest.MAX_ARTIFACT_BYTES);
            String[] paths = new String[manifest.dependencies().size()];
            String[] hashes = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                var dependency = manifest.dependencies().get(i);
                Path target = workspace.directory().resolve(dependency.name());
                extract(LibretroProcess.class, "/core/libretro-jni/windows-x64/" + dependency.name(),
                        dependency.sha256(), target, dependency.bytes());
                if (Files.size(target) != dependency.bytes()) throw new IOException("Bundled runtime length mismatch");
                paths[i] = target.toString(); hashes[i] = dependency.sha256();
            }
            // All bytes are staged and checked before any native entry point can execute.
            pin = workspace.pinNative();
            libraryWorkspace = workspace; libraryPin = pin;
            // A failed/hung OS load can have side effects. Keep its files until process exit.
            loadAttempted = true;
            System.load(dll.toString());
            if (abiVersion() != ABI) throw new IOException("Generic JNI ABI mismatch");
            if (runtimeDependencyApiVersion() != RuntimeDependencyManifest.API)
                throw new IOException("Generic JNI runtime dependency API mismatch");
            retainRuntimeDependencies(paths, hashes);
            loaded = true;
        } catch (IOException | LinkageError | RuntimeException e) {
            if (loadAttempted) {
                loadFailure = "Generic JNI runtime initialization failed; restart required: " + e.getMessage();
                throw new IOException(loadFailure, e);
            }
            throw e;
        } finally {
            if (!loadAttempted) {
                if (pin != null) try { pin.close(); } catch (Exception ignored) { }
                workspace.close();
            } else if (!loaded && loadFailure == null) {
                loadFailure = "Generic JNI runtime initialization did not finish; restart required";
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
    /** Additional capability handshake; existing session ABI stays at 2. */
    public static native int runtimeDependencyApiVersion();
    /** Fixed, verified runtime dependencies only. Held by the bridge until process exit, not core sessions. */
    public static native void retainRuntimeDependencies(String[] absolutePaths, String[] expectedSha256) throws IOException;
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
