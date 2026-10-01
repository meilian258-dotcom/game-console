// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.util.*;

/** Trusted addon declaration, not a user-supplied native-library path or a netplay capability claim. */
public record LibretroProfile(String name, String extension, boolean fullPath, List<Integer> devices,
                              boolean mesenGun, Map<String, String> options,
                              Map<String, Artifact> cores) {
    public record Artifact(String resource, String sha256, long maxBytes) {
        public Artifact(String resource,String sha256){this(resource,sha256,256L*1024*1024);}
        public Artifact {
            if (resource == null || !(resource.startsWith("/core/") || resource.startsWith("/native-runtime/")) || resource.contains("..")
                    || resource.contains("\\") || resource.contains(":")) throw new IllegalArgumentException("Core resource");
            if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Core checksum");
            if(maxBytes<1||maxBytes>512L*1024*1024)throw new IllegalArgumentException("Pinned core byte budget");
        }
    }
    public LibretroProfile {
        if (name == null || name.isBlank() || name.length() > 128 || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Core name");
        if (extension == null || !extension.matches("[a-z0-9]{1,12}")) throw new IllegalArgumentException("Content extension");
        devices = List.copyOf(devices);
        if (devices.isEmpty() || devices.size() > 4 || devices.stream().anyMatch(d -> d < 0 || d > 65535))
            throw new IllegalArgumentException("Controller devices");
        for (int device : devices) if (device != 0 && (device & 255) != 1 && !(mesenGun && device == 262))
            throw new IllegalArgumentException("Unsupported controller device");
        options = Collections.unmodifiableMap(new TreeMap<>(options));
        if (options.size() > 256) throw new IllegalArgumentException("Too many options");
        for (var entry : options.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 || entry.getValue().isEmpty() || entry.getValue().length() > 512
                    || entry.getKey().indexOf('\0') >= 0 || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Core option");
        }
        cores = Map.copyOf(cores);
        if (cores.isEmpty()) throw new IllegalArgumentException("No pinned core artifacts");
        if (mesenGun && (!name.equals("Mesen") || devices.size() != 2 || devices.get(1) != 262))
            throw new IllegalArgumentException("Mesen gun profile");
    }
}
