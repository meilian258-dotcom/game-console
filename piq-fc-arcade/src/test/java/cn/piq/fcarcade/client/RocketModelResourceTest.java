package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RocketModelResourceTest {
    private static final String ROOT = "/assets/piq_fc_arcade/";

    @Test
    void reviewedCabinetGeometryAndUvMatchReleaseFingerprint() throws Exception {
        byte[] model = resource("models/block/rocket_arcade_body.json");
        assertEquals("e4bb95e7ebbb6b989070b952e7ba5d90cf1b16d8a00078bee513a39344ed98f7", sha(model));
        String json = new String(model, StandardCharsets.UTF_8);
        assertTrue(json.contains("piq_fc_arcade:block/rocket_arcade_skin"));
        assertFalse(json.contains("arcade_side_fit:block/skin"));
    }

    @Test
    void reviewedUniversalTextureUsesExact2048Atlas() throws Exception {
        byte[] png = resource("textures/block/rocket_arcade_skin.png");
        assertEquals("789512ed7f867c015c6666d40809845de430e85834ccf7ba4e48bca57de815e8", sha(png));
        assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10}, java.util.Arrays.copyOf(png,8));
        ByteBuffer dimensions = ByteBuffer.wrap(png, 16, 8);
        assertEquals(2048, dimensions.getInt());
        assertEquals(2048, dimensions.getInt());
    }

    @Test
    void idleScreenPixelsAreOpaquePureBlackIncludingSafePadding() throws Exception {
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                resource("textures/block/rocket_arcade_skin.png")));
        try {
            for (int y = 278; y < 535; y++) {
                for (int x = 918; x < 1259; x++) {
                    assertEquals(0xff000000, image.getRGB(x, y), "Idle screen must contain no baked game image");
                }
            }
        } finally { image.flush(); }
    }

    @Test
    void existingBlockAndItemIdsResolveTheNewParent() throws Exception {
        String block = text("models/block/legacy_fc_arcade.json");
        String item = text("models/item/legacy_fc_arcade.json");
        assertTrue(block.contains("piq_fc_arcade:block/legacy_animated/body"));
        assertTrue(block.contains("minecraft:cutout"));
        assertFalse(block.contains("neoforge:obj"));
        assertTrue(item.contains("piq_fc_arcade:block/rocket_arcade_body"));
        assertTrue(item.contains("\"gui\""));
        assertTrue(item.contains("\"ground\""));
        assertTrue(item.contains("\"firstperson_righthand\""));
    }

    @Test
    void originalFourFacingBlockstateMappingIsRetained() throws Exception {
        String states = text("blockstates/legacy_fc_arcade.json");
        for (String facing : new String[]{"north", "east", "south", "west"}) {
            assertTrue(states.contains("facing=" + facing));
        }
        assertEquals(4, states.split("piq_fc_arcade:block/legacy_fc_arcade", -1).length - 1);
        assertFalse(states.contains("half="), "No hidden upper block or state migration is introduced");
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = RocketModelResourceTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }
    private static String text(String name) throws IOException {
        return new String(resource(name), StandardCharsets.UTF_8);
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
