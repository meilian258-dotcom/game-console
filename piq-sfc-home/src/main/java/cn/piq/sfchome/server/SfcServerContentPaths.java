// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/** Pure server content paths and bounded, copy-only legacy import. No world, save or client IO. */
final class SfcServerContentPaths {
    enum Area {
        ROMS("roms", 32L * 1024 * 1024 + 512, 2L * 1024 * 1024 * 1024),
        COVERS("covers", 2L * 1024 * 1024, 128L * 1024 * 1024);
        final String directory;
        final long maxFileBytes, maxBytes;
        Area(String directory, long maxFileBytes, long maxBytes) {
            this.directory = directory; this.maxFileBytes = maxFileBytes; this.maxBytes = maxBytes;
        }
        boolean accepts(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return this == ROMS ? lower.endsWith(".sfc") || lower.endsWith(".smc")
                    : name.matches("[0-9a-f]{64}\\.png");
        }
    }
    private static final int MAX_FILES = 256, MAX_ENTRIES = 512;
    private static final Object[] LOCKS = new Object[32];
    static { Arrays.setAll(LOCKS, ignored -> new Object()); }
    private SfcServerContentPaths() {}

    /** Only relative world location contributes to identity: copying an instance to another disk is stable. */
    static String scope(Path server, Path world) {
        Path base = absolute(server), source = absolute(world);
        final Path relative;
        try { relative = base.relativize(source); }
        catch (IllegalArgumentException differentRoots) {
            throw new IllegalArgumentException("SFC 世界与服务器目录必须能生成相对路径；未使用绝对盘符作为身份", differentRoots);
        }
        String identity = relative.toString().replace('\\', '/');
        if (identity.isEmpty()) identity = ".";
        String label = identity.equals(".") ? "root" : relative.getFileName().toString();
        label = label.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (label.isBlank() || label.equals("_") || label.equals("..")) label = "world";
        if (label.length() > 24) label = label.substring(0, 24);
        return label + "-" + HexFormat.of().formatHex(digest().digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    /** Path construction is IO-free; first prepare belongs to an existing bounded IO worker. */
    static Location location(Path server, Path world, Area area) {
        Objects.requireNonNull(area);
        Path targetBase = absolute(server).resolve("game-console/world-content");
        Path legacy = absolute(world).resolve("game-console/piq-sfc-home").resolve(area.directory);
        try {
            Path target = targetBase.resolve(scope(server, world)).resolve("piq-sfc-home").resolve(area.directory);
            return new Location(target, legacy, area, null);
        } catch (IllegalArgumentException differentRoots) {
            // Stores are constructed by server tick paths. Keep construction IO-free/non-throwing;
            // this sentinel is never accessed because prepare fails before its first filesystem call.
            return new Location(targetBase.resolve("unavailable-world-location").resolve("piq-sfc-home").resolve(area.directory),
                    legacy, area, differentRoots);
        }
    }

    record RejectedFile(Path source, String reason) {}
    static final class Location {
        private final Path root, legacy;
        private final Area area;
        private final IllegalArgumentException unavailable;
        private volatile boolean prepared;
        private volatile List<RejectedFile> rejected = List.of();
        private Location(Path root, Path legacy, Area area, IllegalArgumentException unavailable) {
            this.root = root; this.legacy = legacy; this.area = area; this.unavailable = unavailable;
        }
        Path root() { return root; }
        Path legacy() { return legacy; }
        List<RejectedFile> rejectedFiles() { return rejected; }
        void prepare() throws IOException {
            if (unavailable != null) throw new IOException("SFC 世界与服务器目录不支持跨盘存放；未读取或创建内容目录", unavailable);
            synchronized (LOCKS[Math.floorMod(root.hashCode(), LOCKS.length)]) {
                directory(root, true);
                if (prepared) return;
                List<RejectedFile> failures = new ArrayList<>();
                try {
                    copyLegacy(root, legacy, area, rejected -> {
                        failures.add(rejected);
                        System.getLogger(SfcServerContentPaths.class.getName()).log(System.Logger.Level.WARNING,
                                "SFC 旧内容未导入（原件保留）：{0}；原因：{1}", rejected.source, rejected.reason);
                    });
                } finally { rejected = List.copyOf(failures); }
                // Failure never publishes success; another store/process can safely retry the same import.
                prepared = true;
            }
        }
    }

    private record Source(Path path, BasicFileAttributes attributes, String sha256) {}
    private static void copyLegacy(Path root, Path legacy, Area area, Consumer<RejectedFile> report) throws IOException {
        if (!directory(legacy, false)) return;
        Map<String, Path> old = inventory(legacy, area), current = inventory(root, area);
        List<Source> sources = new ArrayList<>();
        long total = 0;
        for (Path file : current.values()) total = Math.addExact(total, regular(file).size());
        int count = current.size();
        // Preflight every collision before publishing any new file. Never overwrite a differing target.
        for (var entry : old.entrySet()) {
            Path source = entry.getValue();
            BasicFileAttributes before = regular(source);
            Path target = root.resolve(entry.getKey());
            String rejection = sizeOrNameRejection(source, before, area);
            if (rejection != null) {
                rejectLegacy(source, target, rejection, report);
                continue;
            }
            byte[] bytes = readLegacy(source, before, area.maxFileBytes);
            String hash = HexFormat.of().formatHex(digest().digest(bytes));
            try {
                if (area == Area.ROMS) cn.piq.sfcarcade.core.SfcRomImage.fromBytes(bytes);
                else cn.piq.fcarcade.home.CartridgeCoverCodec.validate(bytes, source.getFileName().toString().substring(0, 64));
            } catch (IllegalArgumentException | IOException invalidContent) {
                rejectLegacy(source, target, "内容格式校验失败：" + invalidContent.getMessage(), report);
                continue;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes targetAttributes = regular(target);
                if (targetAttributes.size() != before.size() || !hash.equals(hash(target, targetAttributes, area.maxFileBytes)))
                    throw new IOException("SFC 新旧内容冲突，未覆盖且原件保留：" + source + " -> " + target);
            } else { total = Math.addExact(total, before.size()); count++; }
            if (count > MAX_FILES || total > area.maxBytes) throw new IOException("SFC 迁移后的内容库超出原有容量限制，原件保留");
            sources.add(new Source(source, before, hash));
        }
        for (Source source : sources) {
            Path target = root.resolve(source.path.getFileName());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) copy(source, target, area.maxFileBytes);
            else if (!source.sha256.equals(hash(target, regular(target), area.maxFileBytes)))
                throw new IOException("SFC 迁移目标发生变化，未覆盖：" + target);
        }
        if (!old.keySet().equals(inventory(legacy, area).keySet())) throw new IOException("SFC 旧内容目录在复制期间发生变化，原件保留");
        for (Source source : sources) {
            if (!same(source.attributes, regular(source.path))
                    || !source.sha256.equals(hash(source.path, source.attributes, area.maxFileBytes)))
                throw new IOException("SFC 旧内容在复制期间发生变化，未完成迁移：" + source.path);
        }
    }

    private static String sizeOrNameRejection(Path source, BasicFileAttributes attributes, Area area) {
        long minimum = area == Area.ROMS ? cn.piq.sfcarcade.core.SfcRomImage.MIN_ROM_BYTES : 1;
        if (attributes.size() < minimum || attributes.size() > area.maxFileBytes)
            return "文件大小 " + attributes.size() + " 字节不在允许范围 " + minimum + "～" + area.maxFileBytes;
        if (area == Area.ROMS && source.getFileName().toString().length() > 128) return "ROM 文件名超过 128 字符";
        return null;
    }

    private static void rejectLegacy(Path source, Path target, String reason, Consumer<RejectedFile> report) throws IOException {
        // Invalid data is isolated, but an occupied destination is still a conflict, never a silent winner.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            regular(target);
            throw new IOException("SFC 无效旧内容与目标同名，无法安全核对，未覆盖：" + source + " -> " + target + "；" + reason);
        }
        report.accept(new RejectedFile(source, reason));
    }

    private static byte[] readLegacy(Path path, BasicFileAttributes before, long maxBytes) throws IOException {
        if (before.size() < 1 || before.size() > maxBytes) throw new IOException("SFC 文件大小超限：" + path);
        byte[] bytes;
        try (var in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(Math.toIntExact(before.size()) + 1);
        }
        if (bytes.length != before.size() || !same(before, regular(path))) throw new IOException("SFC 文件读取期间改变：" + path);
        return bytes;
    }

    private static Map<String, Path> inventory(Path root, Area area) throws IOException {
        directory(root, false);
        Map<String, Path> result = new LinkedHashMap<>();
        try (var files = Files.list(root)) {
            List<Path> entries = files.limit(MAX_ENTRIES + 1L).sorted().toList();
            if (entries.size() > MAX_ENTRIES) throw new IOException("SFC 内容目录条目过多，未执行迁移：" + root);
            for (Path file : entries) {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther() || !file.toRealPath().equals(file.toAbsolutePath().normalize()))
                    throw new IOException("SFC 内容目录拒绝链接或重解析点：" + file);
                // No recursion: old hosted staging, saves and unknown files stay exactly where they were.
                if (attributes.isRegularFile() && area.accepts(file.getFileName().toString())) result.put(file.getFileName().toString(), file);
            }
        }
        return result;
    }

    private static void copy(Source source, Path target, long maxBytes) throws IOException {
        directory(target.getParent(), false);
        if (!same(source.attributes, regular(source.path))) throw new IOException("SFC 复制源已改变：" + source.path);
        Path temporary = Files.createTempFile(target.getParent(), ".sfc-content-", ".part");
        try {
            try (var in = FileChannel.open(source.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var out = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(32768); long written = 0;
                while (true) {
                    int n = in.read(buffer); if (n < 0) break; if (n == 0) continue;
                    written += n; if (written > source.attributes.size() || written > maxBytes) throw new IOException("SFC 复制源增长");
                    buffer.flip(); while (buffer.hasRemaining()) out.write(buffer); buffer.clear();
                }
                if (written != source.attributes.size()) throw new IOException("SFC 复制源截断");
                out.force(true);
            }
            if (!same(source.attributes, regular(source.path))
                    || !source.sha256.equals(hash(temporary, regular(temporary), maxBytes))) throw new IOException("SFC 内容复制校验失败");
            directory(target.getParent(), false);
            // No overwrite and no ATOMIC_MOVE: target-exists semantics of atomic moves may replace files.
            Files.move(temporary, target);
            if (!source.sha256.equals(hash(target, regular(target), maxBytes))) throw new IOException("SFC 目标内容校验失败");
        } finally { if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { regular(temporary); Files.delete(temporary); } }
    }

    private static String hash(Path path, BasicFileAttributes before, long maxBytes) throws IOException {
        if (before.size() < 1 || before.size() > maxBytes) throw new IOException("SFC 文件大小超限：" + path);
        MessageDigest digest = digest(); long total = 0;
        try (var in = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(32768);
            while (true) { int n = in.read(buffer); if (n < 0) break; if (n == 0) continue;
                total += n; if (total > before.size()) throw new IOException("SFC 文件读取时增长");
                buffer.flip(); digest.update(buffer); buffer.clear(); }
        }
        if (total != before.size() || !same(before, regular(path))) throw new IOException("SFC 文件读取期间改变：" + path);
        return HexFormat.of().formatHex(digest.digest());
    }
    static boolean directory(Path path, boolean create) throws IOException {
        Path absolute = absolute(path), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return false;
                try { Files.createDirectory(cursor); } catch (FileAlreadyExistsException concurrent) { /* Recheck below. */ }
            }
            var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther() || !cursor.toRealPath().equals(cursor))
                throw new IOException("SFC 目录拒绝链接、重解析点或非目录：" + cursor);
        }
        return true;
    }
    private static BasicFileAttributes regular(Path path) throws IOException {
        if (!directory(path.getParent(), false)) throw new IOException("SFC 文件父目录不存在：" + path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther() || !path.toRealPath().equals(absolute(path)))
            throw new IOException("SFC 内容必须是普通非链接文件：" + path);
        return attributes;
    }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Path absolute(Path path) { return Objects.requireNonNull(path).toAbsolutePath().normalize(); }
}
