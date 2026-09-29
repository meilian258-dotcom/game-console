// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.rom;

import cn.piq.sfcarcade.core.SfcRomImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SfcRomRepository {
    public static final int MAX_SOURCE_BYTES =
            SfcRomImage.MAX_ROM_BYTES + SfcRomImage.COPIER_HEADER_BYTES;

    private final Path root;

    public SfcRomRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public List<SfcRomEntry> list() throws IOException {
        Files.createDirectories(root);
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(SfcRomRepository::isRomPath)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .map(path -> {
                        try {
                            return describe(path);
                        } catch (IOException | IllegalArgumentException ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(256)
                    .toList();
        }
    }

    public SfcRomEntry find(String sha256) throws IOException {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) return null;
        for (SfcRomEntry entry : list()) {
            if (entry.sha256().equals(sha256)) return entry;
        }
        return null;
    }

    public byte[] readVerified(SfcRomEntry entry) throws IOException {
        byte[] bytes = Files.readAllBytes(entry.path());
        SfcRomImage image = SfcRomImage.fromBytes(bytes);
        if (!image.sha256().equals(entry.sha256())) {
            throw new IOException("SFC ROM changed while it was being read");
        }
        return bytes;
    }

    /** Download an already-authorized session's exact file. Never enumerates the library. */
    public byte[] readNamedVerified(String fileName,String sha256) throws IOException {
        String safe=safeFileName(fileName);
        if(!safe.equals(fileName)||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid SFC download identity");
        Path file=root.resolve(safe).normalize();
        if(!file.getParent().equals(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("SFC session ROM is unavailable");
        byte[] bytes;
        try(FileChannel input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=input.size();if(size<cn.piq.sfcarcade.core.SfcRomImage.MIN_ROM_BYTES||size>MAX_SOURCE_BYTES)throw new IOException("SFC ROM size is outside the safety limit");
            bytes=new byte[(int)size];ByteBuffer target=ByteBuffer.wrap(bytes);
            while(target.hasRemaining())if(input.read(target)<0)throw new IOException("SFC ROM changed during download");
            if(input.size()!=size)throw new IOException("SFC ROM changed during download");
        }
        if(!SfcRomImage.fromBytes(bytes).sha256().equals(sha256))throw new IOException("SFC session ROM digest changed");
        return bytes;
    }

    public SfcRomEntry storeVerified(String fileName, String sha256, byte[] bytes)
            throws IOException {
        String safeName = safeFileName(fileName);
        SfcRomImage image = SfcRomImage.fromBytes(bytes);
        if (!image.sha256().equals(sha256)) {
            throw new IOException("Downloaded SFC ROM SHA-256 does not match");
        }
        Files.createDirectories(root);
        Path target = uniqueTarget(safeName, sha256);
        if (Files.exists(target)) {
            SfcRomEntry existing = describe(target);
            if (existing.sha256().equals(sha256)) return existing;
        }
        Path temporary = Files.createTempFile(root, ".piq-sfc-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return describe(target);
    }

    private SfcRomEntry describe(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > MAX_SOURCE_BYTES) {
            throw new IOException("SFC ROM size is outside the safety limit");
        }
        byte[] bytes = Files.readAllBytes(path);
        SfcRomImage image = SfcRomImage.fromBytes(bytes);
        return new SfcRomEntry(
                path.getFileName().toString(),
                image.sha256(),
                bytes.length,
                path);
    }

    private Path uniqueTarget(String fileName, String sha256) throws IOException {
        Path target = root.resolve(fileName).normalize();
        if (!target.getParent().equals(root)) {
            throw new IOException("SFC ROM file escaped its library directory");
        }
        if (!Files.exists(target)) return target;
        try {
            if (describe(target).sha256().equals(sha256)) return target;
        } catch (IOException | IllegalArgumentException ignored) {
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : ".sfc";
        return root.resolve(stem + "-" + sha256.substring(0, 8) + extension);
    }

    private static String safeFileName(String fileName) throws IOException {
        if (fileName == null) throw new IOException("Missing SFC ROM file name");
        String name = Path.of(fileName).getFileName().toString().strip();
        if (name.isBlank() || name.length() > 128 || !isRomName(name)
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid SFC ROM file name");
        }
        return name;
    }

    public static boolean isRomPath(Path path) {
        return isRomName(path.getFileName().toString());
    }

    private static boolean isRomName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sfc") || lower.endsWith(".smc");
    }
}
