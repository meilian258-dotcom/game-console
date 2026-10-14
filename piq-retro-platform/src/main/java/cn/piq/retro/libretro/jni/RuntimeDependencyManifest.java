// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;

import java.io.*;
import java.util.*;

/** The main mod's fixed Windows runtime bundle, never supplied by content or a server. */
final class RuntimeDependencyManifest {
    static final int API = 1;
    static final long MAX_ARTIFACT_BYTES = 16L * 1024 * 1024;
    static final long WORKSPACE_BYTES = 64L * 1024 * 1024;
    private static final String PREFIX = "windows-x64.runtime.";
    record Dependency(String name, String sha256, long bytes) { }
    record Bundle(String bridgeSha256, List<Dependency> dependencies) {
        Bundle { dependencies = List.copyOf(dependencies); }
    }

    static Bundle read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(16 * 1024 + 1);
        if (bytes.length > 16 * 1024) throw new IOException("Generic JNI manifest exceeds budget");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate manifest property: " + key);
                return super.put(key, value);
            }
        };
        try {
            properties.load(new ByteArrayInputStream(bytes));
            if (!Integer.toString(NativeLibretroBridge.ABI).equals(properties.getProperty("abi")))
                throw new IOException("Bundled generic JNI ABI mismatch");
            if (!Integer.toString(API).equals(properties.getProperty("runtime-dependency-api")))
                throw new IOException("Bundled JNI runtime dependency API mismatch; update the main mod as a unit");
            String countText = properties.getProperty(PREFIX + "count", "");
            if (!countText.equals("1") && !countText.equals("2"))
                throw new IOException("Bundled JNI runtime requires one or two fixed dependencies");
            int count = Integer.parseInt(countText);
            Set<String> expected = new HashSet<>(Set.of("abi", "windows-x64.sha256",
                    "runtime-dependency-api", PREFIX + "count"));
            List<Dependency> dependencies = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String key = PREFIX + i + ".";
                expected.addAll(Set.of(key + "name", key + "sha256", key + "bytes"));
                // No caller-selected paths or dependency graph. Unwind, if needed, must load first.
                String name = properties.getProperty(key + "name", "");
                String required = count == 2 && i == 0 ? "libunwind.dll" : "libc++.dll";
                if (!name.equals(required)) throw new IOException("Unexpected bundled JNI runtime name/order");
                String length = properties.getProperty(key + "bytes", "");
                if (!length.matches("[1-9][0-9]{0,7}")) throw new IOException("Invalid bundled runtime length");
                long size = Long.parseLong(length);
                if (size > MAX_ARTIFACT_BYTES) throw new IOException("Bundled runtime exceeds budget");
                dependencies.add(new Dependency(name, hash(properties, key + "sha256"), size));
            }
            if (!properties.stringPropertyNames().equals(expected))
                throw new IOException("Unexpected or missing generic JNI manifest properties");
            return new Bundle(hash(properties, "windows-x64.sha256"), dependencies);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid generic JNI manifest: " + e.getMessage(), e);
        }
    }

    private static String hash(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key, "");
        if (!value.matches("[A-Fa-f0-9]{64}")) throw new IOException("Missing pinned generic JNI artifact: " + key);
        return value.toLowerCase(Locale.ROOT);
    }
    private RuntimeDependencyManifest() { }
}
