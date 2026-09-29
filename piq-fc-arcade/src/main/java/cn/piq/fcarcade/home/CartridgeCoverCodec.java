package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomRepository;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Bounded PNG bitmap processing, never image generation and never unbounded decoder allocation. */
public final class CartridgeCoverCodec {
    private static final byte[] SIGNATURE = {(byte) 137,80,78,71,13,10,26,10};
    public static final int WIDTH = 512, HEIGHT = 256, SKIN_SIZE = 1024;
    private CartridgeCoverCodec() {}
    public static int[] dimensions(byte[] png, int maximumBytes, int maximumDimension) throws IOException {
        if (png == null || png.length < 33 || png.length > maximumBytes) throw new IOException("封面 PNG 字节数无效");
        for (int i = 0; i < 8; i++) if (png[i] != SIGNATURE[i]) throw new IOException("封面只接受 PNG");
        ByteBuffer data = ByteBuffer.wrap(png);
        if (data.getInt(8) != 13 || data.getInt(12) != 0x49484452) throw new IOException("PNG IHDR 无效");
        int width = data.getInt(16), height = data.getInt(20);
        if (width <= 0 || height <= 0 || width > maximumDimension || height > maximumDimension
                || (long) width * height > 4L * 1024 * 1024) throw new IOException("PNG 尺寸过大或无效（最大 2048×2048）");
        return new int[]{width, height};
    }
    public static byte[] prepare(byte[] source) throws IOException {
        int[] size = dimensions(source, CartridgeLimits.MAX_SOURCE_COVER_BYTES, 2048);
        BufferedImage image = decode(source, size);
        BufferedImage target = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, WIDTH, HEIGHT);
            fitCrop(graphics, image, 0, 0, WIDTH, HEIGHT);
            return encode(target);
        } finally { graphics.dispose(); image.flush(); target.flush(); }
    }
    public static void validate(byte[] png, String hash) throws IOException {
        int[] size = dimensions(png, CartridgeLimits.MAX_COVER_BYTES, WIDTH);
        if (size[0] != WIDTH || size[1] != HEIGHT) throw new IOException("上传封面必须已规范为 512×256");
        if (!CartridgeLimits.validHash(hash) || !RomRepository.sha256(png).equals(hash)) throw new IOException("封面 SHA-256 不匹配");
        BufferedImage decoded = decode(png, size);
        try {
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                if ((decoded.getRGB(x, y) >>> 24) != 255)
                    throw new IOException("封面标签必须不透明，请通过客户端规范化上传");
            }
        } finally { decoded.flush(); }
    }
    public static byte[] compose(byte[] baseSkin, byte[] cover, String hash) throws IOException {
        validate(cover, hash);
        int[] baseSize = dimensions(baseSkin, CartridgeLimits.MAX_SOURCE_COVER_BYTES, SKIN_SIZE);
        if (baseSize[0] != SKIN_SIZE || baseSize[1] != SKIN_SIZE) throw new IOException("卡带原贴图必须为 1024×1024");
        BufferedImage base = decode(baseSkin, baseSize);
        BufferedImage label = decode(cover, new int[]{WIDTH, HEIGHT});
        BufferedImage result = new BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            // Preserve decoded RGBA exactly outside the label, including transparent RGB.
            result.setRGB(0, 0, SKIN_SIZE, SKIN_SIZE, base.getRGB(0, 0, SKIN_SIZE, SKIN_SIZE, null, 0, SKIN_SIZE), 0, SKIN_SIZE);
            // Actual label UV is exactly [32,32,544,288). Replace all of it,
            // then extend its edge texels outward 4px; adjacent UV islands were audited disjoint.
            graphics.drawImage(label, 32, 32, null);
            for (int y = 28; y < 292; y++) {
                for (int x = 28; x < 548; x++) {
                    if (x >= 32 && x < 544 && y >= 32 && y < 288) continue;
                    int sx = Math.max(0, Math.min(WIDTH - 1, x - 32));
                    int sy = Math.max(0, Math.min(HEIGHT - 1, y - 32));
                    result.setRGB(x, y, label.getRGB(sx, sy));
                }
            }
            return encode(result);
        } finally { graphics.dispose(); base.flush(); label.flush(); result.flush(); }
    }
    private static void fitCrop(Graphics2D graphics, BufferedImage source, int x, int y, int width, int height) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.max((double) width / source.getWidth(), (double) height / source.getHeight());
        int drawWidth = (int) Math.ceil(source.getWidth() * scale), drawHeight = (int) Math.ceil(source.getHeight() * scale);
        graphics.drawImage(source, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                drawWidth, drawHeight, null);
    }
    private static BufferedImage decode(byte[] png, int[] expected) throws IOException {
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(png));
        if (result == null) throw new IOException("PNG 无法解码");
        if (result.getWidth() != expected[0] || result.getHeight() != expected[1]) {
            result.flush(); throw new IOException("PNG 解码尺寸不符");
        }
        return result;
    }
    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("缺少 PNG 编码器");
        return output.toByteArray();
    }
}
