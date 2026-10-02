package cn.piq.fcarcade.home.content;

import cn.piq.fcarcade.cabinet.CabinetGameStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;

/** First-upload names only, separate from ROM files and card titles. IO-worker only. */
final class ContentCardNames {
    static final int MAX_NAME_BYTES = 512;
    private final Path root;

    ContentCardNames(Path root) { this.root = root.toAbsolutePath().normalize(); }

    String read(String hash) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        CabinetGameStore.requireDirectory(root);
        Path path = path(hash);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        var before = CabinetGameStore.regular(path);
        if (before.size() < 1 || before.size() > MAX_NAME_BYTES) throw new IOException("库名元数据大小无效");
        byte[] bytes;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(MAX_NAME_BYTES + 1); }
        var after = CabinetGameStore.regular(path);
        if (bytes.length != before.size() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("库名读取期间发生变化");
        try {
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            validate(name);
            return name;
        } catch (IllegalArgumentException invalid) { throw new IOException("库名元数据内容无效", invalid); }
    }

    /** No rename API: a second uploader cannot change the first accepted global name. */
    String remember(String hash, String originalName) throws IOException {
        validate(originalName);
        CabinetGameStore.directory(root);
        Path lockPath = root.resolve("catalog.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) CabinetGameStore.regular(lockPath);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("游戏库名称正在写入，请稍后重试");
                String prior = read(hash);
                if (prior != null) return prior;
                try (var files = Files.list(root)) {
                    // One lock plus at most one name per content; do not silently discard stale/unknown files.
                    var paths = files.limit(ContentCardStore.MAX_FILES + 2L).toList();
                    if (paths.size() > ContentCardStore.MAX_FILES) throw new IOException("游戏库名称目录已满，请管理员检查");
                    for (Path path : paths) {
                        var attrs = CabinetGameStore.regular(path);
                        if (attrs.size() > MAX_NAME_BYTES) throw new IOException("游戏库名称目录存在超限文件");
                    }
                }
                Path target = path(hash), temporary = root.resolve(".name-" + UUID.randomUUID() + ".part");
                try {
                    try (var out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        var bytes = ByteBuffer.wrap(originalName.getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) out.write(bytes);
                        out.force(true);
                    }
                    // No non-atomic fallback: a failed write cannot report a durable display name.
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    String persisted = read(hash);
                    if (!originalName.equals(persisted)) throw new IOException("游戏库名称写入校验失败");
                    return persisted;
                } finally { Files.deleteIfExists(temporary); }
            } catch (OverlappingFileLockException busy) { throw new IOException("游戏库名称正在写入，请稍后重试", busy); }
        }
    }

    private Path path(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid content hash");
        return root.resolve(hash + ".name");
    }

    static void validate(String name) {
        if (name == null || name.isBlank() || name.length() > 128
                || name.chars().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(name)
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES)
            throw new IllegalArgumentException("Invalid content display name");
    }

    static String fallback(String fileName) {
        return fileName != null && fileName.matches("[0-9a-f]{64}\\.[A-Za-z0-9]+")
                ? "未命名内容 · " + fileName.substring(0, 12) + "（名称缺失）" : fileName;
    }
}
