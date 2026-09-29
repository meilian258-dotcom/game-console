// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.rom;

import java.nio.file.Path;

public record SfcRomEntry(String fileName, String sha256, int size, Path path) {
    public SfcRomEntry {
        if (fileName == null || fileName.isBlank() || fileName.length() > 128) {
            throw new IllegalArgumentException("Invalid SFC ROM file name");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SFC ROM SHA-256");
        }
        if (size <= 0 || size > SfcRomRepository.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Invalid SFC ROM size");
        }
        path = path.toAbsolutePath().normalize();
    }
}
