package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomRepository;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeCoverCodecTest {
    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    static byte[] label() throws IOException {
        BufferedImage image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) image.setRGB(x, y, (x % 256 << 16) | (y << 8) | (x * 7 + y) % 256);
        return png(image);
    }
    @Test void prepareCropsToCanonicalOpaque512By256WithoutMutatingSource() throws Exception {
        BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(40, 40, 0xFFFF0000); byte[] source = png(image), original = source.clone();
        byte[] prepared = CartridgeCoverCodec.prepare(source);
        CartridgeCoverCodec.validate(prepared, RomRepository.sha256(prepared));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared));
        assertEquals(512, result.getWidth()); assertEquals(256, result.getHeight());
        assertEquals(0xFFFFFFFF, result.getRGB(0, 0)); assertArrayEquals(original, source);
    }
    @Test void rejectsOversizedHeaderBeforeAnyDecoderAllocation() throws Exception {
        byte[] source = label(); ByteBuffer.wrap(source).putInt(16, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> CartridgeCoverCodec.prepare(source));
        assertThrows(IOException.class, () -> CartridgeCoverCodec.dimensions(new byte[32], 1024, 2048));
        assertThrows(IOException.class, () -> CartridgeCoverCodec.prepare(new byte[CartridgeLimits.MAX_SOURCE_COVER_BYTES + 1]));
    }
    @Test void rejectsWrongHashWrongDimensionsAndTransparentDirectUploads() throws Exception {
        byte[] source = label();
        assertThrows(IOException.class, () -> CartridgeCoverCodec.validate(source, "0".repeat(64)));
        byte[] square = png(new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB));
        assertThrows(IOException.class, () -> CartridgeCoverCodec.validate(square, RomRepository.sha256(square)));
        byte[] transparent = png(new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB));
        assertThrows(IOException.class, () -> CartridgeCoverCodec.validate(transparent, RomRepository.sha256(transparent)));
    }
    @Test void replacesEntireRealLabelAndExtrudesAllFourEdgesAndCornersLeavingEverythingElseExact() throws Exception {
        BufferedImage base = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++)
            base.setRGB(x, y, ((x + y) % 256 << 24) | 0x00CC0099 ^ ((x * 13 + y * 7) & 255));
        byte[] labelPng = label(), basePng = png(base); byte[] baseOriginal = basePng.clone(), labelOriginal = labelPng.clone();
        BufferedImage cover = ImageIO.read(new ByteArrayInputStream(labelPng));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(CartridgeCoverCodec.compose(basePng, labelPng, RomRepository.sha256(labelPng))));
        int[] expected = base.getRGB(0, 0, 1024, 1024, null, 0, 1024);
        for (int y = 28; y < 292; y++) for (int x = 28; x < 548; x++)
            expected[y * 1024 + x] = cover.getRGB(Math.max(0, Math.min(511, x - 32)), Math.max(0, Math.min(255, y - 32)));
        assertArrayEquals(expected, actual.getRGB(0, 0, 1024, 1024, null, 0, 1024));
        assertArrayEquals(baseOriginal, basePng); assertArrayEquals(labelOriginal, labelPng);
    }
    @Test void rejectsWrongBaseSkinDimensionsWithoutReturningApproximateMapping() throws Exception {
        byte[] base = png(new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)); byte[] cover = label();
        assertThrows(IOException.class, () -> CartridgeCoverCodec.compose(base, cover, RomRepository.sha256(cover)));
    }
    @Test void canonicalEncodingIsDeterministic() throws Exception {
        byte[] image = label(); assertArrayEquals(CartridgeCoverCodec.prepare(image), CartridgeCoverCodec.prepare(image));
    }
}
