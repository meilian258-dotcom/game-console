// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.rom;

import cn.piq.retro.storage.ServerContentPaths;
import cn.piq.sfcarcade.core.SfcRomImage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Old independent cabinet content only; never migrates saves, clients, worlds or unknown files. */
public final class LegacySfcContentPaths {
    private static final int MAX_ENTRIES = 512, MAX_FILES = 256;
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private final Path root, legacy;

    public LegacySfcContentPaths(Path instance) {
        root = ServerContentPaths.instanceArea(instance, "piq-sfc-arcade", "roms");
        legacy = instance.toAbsolutePath().normalize().resolve("sfc-roms");
    }
    public Path root() { return root; }
    public Path legacy() { return legacy; }
    public record Report(int copied, int reused, List<String> rejected) {
        public Report { rejected = List.copyOf(rejected); }
    }
    private record Source(Path path, BasicFileAttributes attributes, String digest) {}

    /** Must run in bounded background IO. Copy-only and retryable; conflicting destinations block success. */
    public Report prepare() throws IOException {
        directory(root, true);
        if (!directory(legacy, false)) return new Report(0, 0, List.of());
        Map<String, Path> old = inventory(legacy), current = inventory(root);
        List<Source> sources = new ArrayList<>(); List<String> rejected = new ArrayList<>();
        long total = 0; int count = current.size();
        for (Path file : current.values()) total = Math.addExact(total, regular(file).size());
        if (count > MAX_FILES || total > MAX_BYTES) throw new IOException("SFC 新内容库超出容量限制，未导入旧文件");
        for (var entry : old.entrySet()) {
            interrupted();
            Path source = entry.getValue(), target = root.resolve(entry.getKey());
            BasicFileAttributes before = regular(source);
            byte[] bytes;
            try {
                if (entry.getKey().length() > 128) throw new IOException("文件名超过 128 字符");
                bytes = read(source, before);
                SfcRomImage.fromBytes(bytes);
            } catch (IllegalArgumentException | IOException invalid) {
                interrupted();
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("无效旧 SFC 内容与新库同名；未覆盖：" + source, invalid);
                rejected.add(source.getFileName() + ": " + invalid.getMessage());
                continue;
            }
            String digest = sha(bytes);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!digest.equals(sha(read(target, regular(target)))))
                    throw new IOException("SFC 新旧文件冲突，原件保留且未覆盖：" + source + " -> " + target);
            } else { count++; total = Math.addExact(total, before.size()); }
            if (count > MAX_FILES || total > MAX_BYTES) throw new IOException("SFC 导入后超过 256 文件或 2 GiB，原件保留");
            sources.add(new Source(source, before, digest));
        }
        int copied = 0, reused = 0;
        for (Source source : sources) {
            interrupted();
            Path target = root.resolve(source.path.getFileName());
            if (!same(source.attributes, regular(source.path))) throw new IOException("SFC 旧内容在复制前改变");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!source.digest.equals(sha(read(target, regular(target))))) throw new IOException("SFC 目标在复制期间改变");
                reused++;
                continue;
            }
            Path temporary = Files.createTempFile(root, ".legacy-sfc-", ".part");
            try {
                byte[] bytes = read(source.path, source.attributes);
                if (!source.digest.equals(sha(bytes))) throw new IOException("SFC 旧内容在复制期间改变");
                try (var out = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) { interrupted(); out.write(buffer); }
                    out.force(true);
                }
                if (!source.digest.equals(sha(read(temporary, regular(temporary))))) throw new IOException("SFC 复制校验失败");
                directory(root, false);
                interrupted();
                // No REPLACE_EXISTING or ATOMIC_MOVE: both can overwrite a concurrently published target.
                Files.move(temporary, target);
                if (!source.digest.equals(sha(read(target, regular(target))))) throw new IOException("SFC 导入目标校验失败");
                copied++;
            } finally { if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { regular(temporary); Files.delete(temporary); } }
        }
        if (!old.keySet().equals(inventory(legacy).keySet())) throw new IOException("SFC 旧目录在复制期间改变，原件保留");
        for (Source source : sources) {
            if (!same(source.attributes, regular(source.path)) || !source.digest.equals(sha(read(source.path, source.attributes))))
                throw new IOException("SFC 旧内容在复制后改变，未确认导入完成");
        }
        return new Report(copied, reused, rejected);
    }

    private static Map<String, Path> inventory(Path path) throws IOException {
        directory(path, false); var result = new LinkedHashMap<String, Path>();
        try (var files = Files.list(path)) {
            List<Path> entries = files.limit(MAX_ENTRIES + 1L).sorted().toList();
            if (entries.size() > MAX_ENTRIES) throw new IOException("SFC 目录超过 512 项，未执行导入");
            for (Path file : entries) {
                var a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther() || !file.toRealPath().equals(file.toAbsolutePath().normalize()))
                    throw new IOException("SFC 目录拒绝链接或重解析点：" + file);
                if (a.isRegularFile() && SfcRomRepository.isRomPath(file)) result.put(file.getFileName().toString(), file);
            }
        }
        return result;
    }
    static byte[] read(Path file, BasicFileAttributes before) throws IOException {
        interrupted();
        if (before.size() < SfcRomImage.MIN_ROM_BYTES || before.size() > SfcRomRepository.MAX_SOURCE_BYTES)
            throw new IOException("SFC 文件大小超出范围：" + before.size());
        byte[] bytes;
        try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(Math.toIntExact(before.size()) + 1);
        }
        if (bytes.length != before.size() || !same(before, regular(file))) throw new IOException("SFC 文件读取期间改变");
        interrupted();
        return bytes;
    }
    public static void directory(Path path) throws IOException { directory(path, true); }
    private static boolean directory(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return false;
                try { Files.createDirectory(cursor); } catch (FileAlreadyExistsException concurrent) { /* Recheck below. */ }
            }
            var a = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther() || !cursor.toRealPath().equals(cursor))
                throw new IOException("SFC 目录拒绝链接或非目录：" + cursor);
        }
        return true;
    }
    static BasicFileAttributes regular(Path path) throws IOException {
        if (!directory(path.getParent(), false)) throw new IOException("SFC 父目录不存在");
        var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther() || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("SFC 内容不是普通文件：" + path);
        return a;
    }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("SFC 内容导入已取消；原件保留");
    }
}
