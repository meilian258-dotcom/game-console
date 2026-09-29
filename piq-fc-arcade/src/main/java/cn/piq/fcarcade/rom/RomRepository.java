package cn.piq.fcarcade.rom;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class RomRepository {
    public static final int MAX_ROM_BYTES = 32 * 1024 * 1024;
    public static final String SHA256_PATTERN = "[0-9a-f]{64}";
    private final Path root;

    public RomRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public RomDescriptor load(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("ROM 文件名不能为空");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".nes")) {
            throw new IllegalArgumentException("只允许加载 .nes 文件");
        }

        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("ROM 路径不能离开配置目录");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("找不到 ROM：" + path);
        }

        long size = Files.size(path);
        if (size <= 0 || size > MAX_ROM_BYTES) {
            throw new IOException("ROM 大小必须在 1 字节至 " + MAX_ROM_BYTES + " 字节之间");
        }

        byte[] bytes = Files.readAllBytes(path);
        INesHeader header = INesHeader.parse(bytes);
        return new RomDescriptor(path, path.getFileName().toString(), sha256(bytes), header, bytes);
    }

    public List<RomDescriptor> list() throws IOException {
        Files.createDirectories(root);
        List<RomDescriptor> result = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(
                            candidate,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> candidate.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".nes"))
                    .sorted(Comparator.comparing(
                            candidate -> candidate.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                try {
                    result.add(load(path.getFileName().toString()));
                } catch (IOException | IllegalArgumentException ignored) {
                    // One broken file must not hide the rest of the library.
                }
            }
        }
        return List.copyOf(result);
    }

    public RomDescriptor findBySha256(String sha256) throws IOException {
        String normalized = normalizeSha256(sha256);
        for (RomDescriptor descriptor : list()) {
            if (descriptor.sha256().equals(normalized)) return descriptor;
        }
        return null;
    }

    public boolean deleteBySha256(String sha256) throws IOException {
        RomDescriptor descriptor = findBySha256(sha256);
        return descriptor != null && Files.deleteIfExists(descriptor.path());
    }

    public RomDescriptor storeVerified(
            String suggestedFileName,
            String expectedSha256,
            byte[] bytes
    ) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ROM_BYTES) {
            throw new IllegalArgumentException(
                    "ROM 大小必须在 1 字节至 " + MAX_ROM_BYTES + " 字节之间");
        }
        String normalizedHash = normalizeSha256(expectedSha256);
        String actualHash = sha256(bytes);
        if (!actualHash.equals(normalizedHash)) {
            throw new IllegalArgumentException("ROM SHA-256 校验失败");
        }
        INesHeader header = INesHeader.parse(bytes);
        NesCompatibility.requireSupported(header);

        Files.createDirectories(root);
        RomDescriptor existing = findBySha256(normalizedHash);
        if (existing != null) return existing;

        String safeName = safeFileName(suggestedFileName);
        Path destination = root.resolve(safeName).normalize();
        if (!destination.startsWith(root)) {
            throw new IllegalArgumentException("ROM 路径不能离开配置目录");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            String base = safeName.substring(0, safeName.length() - 4);
            destination = root.resolve(
                    base + "-" + normalizedHash.substring(0, 12) + ".nes");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString(), null,
                    "ROM 文件名及备用文件名均已占用；原文件保留，请更换上传名称");
        }

        Path temporary = Files.createTempFile(root, ".piq-rom-", ".tmp");
        boolean moved = false;
        try {
            Files.write(
                    temporary,
                    bytes,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // No REPLACE_EXISTING, and deliberately no ATOMIC_MOVE: its target-exists
            // semantics may replace files even without REPLACE_EXISTING (including on Windows).
            // The server library serializes this publication and only indexes after success.
            Files.move(temporary, destination);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
        return new RomDescriptor(
                destination,
                destination.getFileName().toString(),
                normalizedHash,
                header,
                bytes);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private static String normalizeSha256(String sha256) {
        if (sha256 == null) throw new IllegalArgumentException("ROM SHA-256 不能为空");
        String normalized = sha256.toLowerCase(Locale.ROOT);
        if (!normalized.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 格式无效");
        }
        return normalized;
    }

    private static String safeFileName(String suggestedFileName) {
        String candidate = suggestedFileName == null
                ? "game.nes"
                : suggestedFileName.trim();
        candidate = candidate.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        candidate = candidate.replaceAll("^\\.+", "");
        if (candidate.isBlank()) candidate = "game.nes";
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(".nes")) {
            candidate += ".nes";
        }
        if (candidate.length() > 120) {
            candidate = candidate.substring(0, 116) + ".nes";
        }
        return candidate;
    }
}
