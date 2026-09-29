package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the packaged resource, without loading Minecraft's registries in a unit-test VM. */
class HomeHardwareLootResourceTest {
    @Test
    void retroTvLootResourceDropsExactlyOneTelevision() throws IOException {
        assertSelfDrop("retro_tv");
    }

    @Test
    void originalFamicomLootResourceStillDropsExactlyOneConsole() throws IOException {
        assertSelfDrop("famicom_console");
    }

    @Test
    void suborLootResourceDropsExactlyOneOwnConsoleWithoutCopiedHardwareNbt() throws IOException {
        assertSelfDrop("subor_console");
    }

    @Test
    void lcdLootResourceDropsOneLcdRatherThanACrtOrHardwareContents() throws IOException {
        assertSelfDrop("lcd_tv");
    }

    private static void assertSelfDrop(String blockId) throws IOException {
        String path = "/data/piq_fc_arcade/loot_table/blocks/" + blockId + ".json";
        try (var input = HomeHardwareLootResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "Missing packaged survival drop table: " + path);
            String actual = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            // Deliberately lock this small complete schema: one roll, one own
            // block item, no inventory-copy function or duplicate extra pool.
            // This also catches a valid-looking name stranded outside entries.
            String expected = """
                    {
                      "type": "minecraft:block",
                      "pools": [{
                        "bonus_rolls": 0.0,
                        "conditions": [{"condition": "minecraft:survives_explosion"}],
                        "entries": [{"type": "minecraft:item", "name": "piq_fc_arcade:%s"}],
                        "rolls": 1.0
                      }],
                      "random_sequence": "piq_fc_arcade:blocks/%s"
                    }
                    """.formatted(blockId, blockId);
            assertEquals(expected.replaceAll("\\s+", ""), actual.replaceAll("\\s+", ""),
                    "Hardware contents drop separately; the block table must only drop itself once");
        }
    }
}
