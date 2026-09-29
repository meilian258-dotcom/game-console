package cn.piq.fcarcade.skin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkinImageCodecTest {
    @Test
    void keepsOriginal2048PixelsAndPngBytesWithoutResizing() throws Exception {
        byte[] source = png(2048, 2048);
        SkinImageCodec.Prepared prepared = SkinImageCodec.prepare(source);

        assertTrue(prepared.sha256().matches("[0-9a-f]{64}"));
        SkinImageCodec.validatePrepared(
                prepared.png(),
                prepared.sha256());
        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(prepared.png()));
        assertEquals(2048, decoded.getWidth());
        assertEquals(2048, decoded.getHeight());
        assertEquals(0xFFFF00FF, decoded.getRGB(0, 0));
        org.junit.jupiter.api.Assertions.assertArrayEquals(source, prepared.png());
    }

    @Test
    void rejectsHashMismatch() throws Exception {
        SkinImageCodec.Prepared prepared =
                SkinImageCodec.prepare(png(2048, 2048));
        assertThrows(
                IOException.class,
                () -> SkinImageCodec.validatePrepared(
                        prepared.png(),
                        "0".repeat(64)));
    }

    @Test
    void rejectsNonImageData() {
        assertThrows(
                IOException.class,
                () -> SkinImageCodec.prepare(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void legacy512RemainsReadableButCannotBePreparedOrUploaded() throws Exception {
        byte[] legacy = png(512, 512);
        String hash = SkinImageCodec.sha256(legacy);
        assertEquals(SkinLayout.LEGACY_512, SkinImageCodec.validateStored(legacy, hash));
        assertThrows(IOException.class, () -> SkinImageCodec.prepare(legacy));
        assertThrows(IOException.class, () -> SkinImageCodec.validatePrepared(legacy, hash));
        assertThrows(IOException.class, () -> SkinImageCodec.prepare(png(1024, 1024)));
    }

    @Test
    void oversizedIhdrIsRejectedBeforeImageDecoderRuns() throws Exception {
        byte[] header = java.util.Arrays.copyOf(png(2048, 2048), 33);
        java.nio.ByteBuffer.wrap(header).putInt(16, 1_000_000).putInt(20, 1_000_000);
        IOException failure = assertThrows(IOException.class, () -> SkinImageCodec.prepare(header));
        assertTrue(failure.getMessage().contains("2048"));
        header[0] = 0;
        assertThrows(IOException.class, () -> SkinImageCodec.inspectHeader(header));
    }

    @Test
    void sourceAndWireSizeAreLimitedTo16MiB() {
        assertEquals(16 * 1024 * 1024, SkinTransferLimits.MAX_SOURCE_BYTES);
        assertEquals(16 * 1024 * 1024, SkinTransferLimits.MAX_PNG_BYTES);
        assertThrows(IOException.class,
                () -> SkinImageCodec.prepare(new byte[SkinTransferLimits.MAX_SOURCE_BYTES + 1]));
    }

    @Test
    void bundledCurrentTemplateIsAcceptedWithoutDownsampling() throws Exception {
        byte[] png;
        try (var input = getClass().getResourceAsStream(
                "/assets/piq_fc_arcade/textures/block/rocket_arcade_skin.png")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            png = input.readAllBytes();
        }
        assertTrue(png.length > 0 && png.length <= SkinTransferLimits.MAX_PNG_BYTES);
        var prepared = SkinImageCodec.prepare(png);
        assertEquals(SkinLayout.ROCKET_V1_2048, SkinImageCodec.inspectHeader(prepared.png()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(png, prepared.png());
    }

    @Test
    void validTextureLargerThanOldOneMiBLimitIsStillAccepted() throws Exception {
        BufferedImage image = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(0x504951L);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 2048; x++) image.setRGB(x, y, 0xff000000 | random.nextInt(0x1000000));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        image.flush();
        byte[] png = output.toByteArray();
        assertTrue(png.length > 1024 * 1024);
        org.junit.jupiter.api.Assertions.assertArrayEquals(png, SkinImageCodec.prepare(png).png());
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF00FF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
}
