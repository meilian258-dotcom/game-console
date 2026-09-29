package cn.piq.fcarcade.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SkinImageCodec {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private SkinImageCodec() {}

    /** Keep the original 2048 pixels and PNG bytes: no resizing or lossy re-encoding. */
    public static Prepared prepare(byte[] source) throws IOException {
        requireSize(source, SkinTransferLimits.MAX_SOURCE_BYTES);
        if (!decodeAndValidate(source).compatible()) {
            throw new IOException("旧版 512×512 皮肤 UV 不兼容，请使用新版 2048×2048 火箭车模板");
        }
        return new Prepared(source, sha256(source));
    }

    public static void validatePrepared(byte[] png, String expectedSha256) throws IOException {
        if (!validateStored(png, expectedSha256).compatible()) {
            throw new IOException("新上传仅接受 2048×2048 火箭车 PNG，旧版 512×512 皮肤不能应用");
        }
    }

    /** Legacy cache files are readable and preserved, but never treated as current-layout skins. */
    public static SkinLayout validateStored(byte[] png, String expectedSha256) throws IOException {
        requireSize(png, SkinTransferLimits.MAX_PNG_BYTES);
        if (!sha256(png).equals(expectedSha256)) throw new IOException("皮肤 SHA-256 校验失败");
        return decodeAndValidate(png);
    }

    public static byte[] readBounded(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > SkinTransferLimits.MAX_SOURCE_BYTES) {
            throw new IOException("皮肤 PNG 必须在 1 字节至 16 MiB 之间");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(SkinTransferLimits.MAX_SOURCE_BYTES + 1);
            requireSize(bytes, SkinTransferLimits.MAX_SOURCE_BYTES);
            return bytes;
        }
    }

    /** Read only the bounded PNG header for catalogs; full validation happens before using bytes. */
    public static SkinLayout inspect(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > SkinTransferLimits.MAX_SOURCE_BYTES) throw new IOException("皮肤 PNG 超过 16 MiB");
        try (var input = Files.newInputStream(path)) { return inspectHeader(input.readNBytes(33)); }
    }

    public static SkinLayout inspectHeader(byte[] header) throws IOException {
        if (header == null || header.length < 33) throw new IOException("PNG 头不完整");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (header[i] != PNG_SIGNATURE[i]) throw new IOException("只支持 PNG 皮肤文件");
        }
        ByteBuffer bytes = ByteBuffer.wrap(header);
        if (bytes.getInt(8) != 13 || bytes.getInt(12) != 0x49484452) throw new IOException("PNG 缺少合法 IHDR");
        try {
            return SkinLayout.fromDimensions(bytes.getInt(16), bytes.getInt(20));
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static SkinLayout decodeAndValidate(byte[] png) throws IOException {
        // Reject decompression bombs BEFORE ImageIO/NativeImage allocates any pixel buffer.
        SkinLayout layout = inspectHeader(png);
        BufferedImage decoded;
        try (var input = new ByteArrayInputStream(png)) { decoded = ImageIO.read(input); }
        if (decoded == null) throw new IOException("文件不是有效 PNG");
        try {
            if (decoded.getWidth() != layout.size() || decoded.getHeight() != layout.size()) {
                throw new IOException("PNG 解码尺寸与 UV 布局不一致");
            }
        } finally { decoded.flush(); }
        return layout;
    }

    private static void requireSize(byte[] bytes, int maximum) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
            throw new IOException("皮肤 PNG 必须在 1 字节至 16 MiB 之间");
        }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JVM 缺少 SHA-256", impossible); }
    }

    public record Prepared(byte[] png, String sha256) {
        public Prepared { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
}
