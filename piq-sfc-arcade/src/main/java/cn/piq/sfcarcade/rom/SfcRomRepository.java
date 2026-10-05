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
        LegacySfcContentPaths.directory(root);
        try (var paths = Files.list(root)) {
            List<Path> candidates = paths.limit(513).toList();
            if (candidates.size() > 512) throw new IOException("SFC library contains more than 512 entries");
            return candidates.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(SfcRomRepository::isRomPath)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .map(path -> {
                        try {
                            return describe(path);
                        } catch (IOException | IllegalArgumentException ignored) {
                            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
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
        if (!entry.path().toAbsolutePath().normalize().getParent().equals(root)) throw new IOException("SFC entry escaped its library");
        byte[] bytes = LegacySfcContentPaths.read(entry.path(), LegacySfcContentPaths.regular(entry.path()));
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
        byte[] bytes = LegacySfcContentPaths.read(file, LegacySfcContentPaths.regular(file));
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
        LegacySfcContentPaths.directory(root);
        Path target = uniqueTarget(safeName, sha256);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            SfcRomEntry existing = describe(target);
            if (existing.sha256().equals(sha256)) return existing;
            throw new IOException("SFC ROM destination already exists with different content");
        }
        checkCapacity(bytes.length);
        Path temporary = Files.createTempFile(root, ".piq-sfc-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { LegacySfcContentPaths.interrupted(); output.write(buffer); }
                output.force(true);
            }
            if (!describe(temporary).sha256().equals(sha256)) throw new IOException("SFC temporary ROM digest changed");
            LegacySfcContentPaths.directory(root);
            LegacySfcContentPaths.interrupted();
            // No overwrite, even if another process creates a same-name ROM after uniqueTarget().
            Files.move(temporary, target);
        } finally {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { LegacySfcContentPaths.regular(temporary); Files.delete(temporary); }
        }
        return describe(target);
    }

    private SfcRomEntry describe(Path path) throws IOException {
        byte[] bytes = LegacySfcContentPaths.read(path, LegacySfcContentPaths.regular(path));
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
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        try {
            if (describe(target).sha256().equals(sha256)) return target;
        } catch (IOException | IllegalArgumentException ignored) {
            LegacySfcContentPaths.interrupted();
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : ".sfc";
        if (stem.length() > 128 - 9 - extension.length()) stem = stem.substring(0, 128 - 9 - extension.length());
        return root.resolve(stem + "-" + sha256.substring(0, 8) + extension);
    }

    private static String safeFileName(String fileName) throws IOException {
        if (fileName == null) throw new IOException("Missing SFC ROM file name");
        String name;
        try { name = Path.of(fileName).getFileName().toString().strip(); }
        catch (java.nio.file.InvalidPathException invalid) { throw new IOException("Invalid SFC ROM file name", invalid); }
        if (name.isBlank() || name.length() > 128 || !isRomName(name)
                || !name.equals(fileName) || name.contains(":") || name.contains("\\") || name.contains("/")
                || name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])\\..*")
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid SFC ROM file name");
        }
        return name;
    }

    private void checkCapacity(int additionalBytes) throws IOException {
        long total = additionalBytes; int count = 1;
        try (var paths = Files.list(root)) {
            List<Path> files = paths.limit(513).toList();
            if (files.size() > 512) throw new IOException("SFC library contains more than 512 entries");
            for (Path file : files) if (isRomPath(file)) {
                count++; total = Math.addExact(total, LegacySfcContentPaths.regular(file).size());
            }
        }
        if (count > 256 || total > 2L * 1024 * 1024 * 1024) throw new IOException("SFC library exceeds 256 ROMs or 2 GiB");
    }

    public static boolean isRomPath(Path path) {
        return isRomName(path.getFileName().toString());
    }

    private static boolean isRomName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sfc") || lower.endsWith(".smc");
    }
}
