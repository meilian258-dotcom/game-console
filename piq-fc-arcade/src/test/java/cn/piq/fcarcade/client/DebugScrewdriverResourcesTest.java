package cn.piq.fcarcade.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DebugScrewdriverResourcesTest {
    private static final String ROOT = "/assets/piq_fc_arcade/";

    private JsonObject json(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void modelHasSolidGeometryAndResolvableVanillaMaterials() throws Exception {
        var model = json("models/item/debug_screwdriver.json");
        assertEquals("minecraft:block/block", model.get("parent").getAsString());
        var textures = model.getAsJsonObject("textures");
        var materials = Set.of("minecraft:block/red_concrete", "minecraft:block/gray_concrete", "minecraft:block/iron_block");
        for (var entry : textures.entrySet()) assertTrue(materials.contains(entry.getValue().getAsString()));
        var elements = model.getAsJsonArray("elements");
        assertTrue(elements.size() >= 3, "Handle, shaft and tip must have independent solid geometry");
        for (var value : elements) {
            var element = value.getAsJsonObject();
            for (int axis = 0; axis < 3; axis++) {
                double low = element.getAsJsonArray("from").get(axis).getAsDouble();
                double high = element.getAsJsonArray("to").get(axis).getAsDouble();
                assertTrue(Double.isFinite(low) && Double.isFinite(high) && low < high && low >= 0 && high <= 16);
            }
            var faces = element.getAsJsonObject("faces");
            assertEquals(Set.of("north", "south", "east", "west", "up", "down"), faces.keySet());
            for (var face : faces.entrySet()) {
                var def = face.getValue().getAsJsonObject();
                var reference = def.get("texture").getAsString();
                assertTrue(reference.startsWith("#") && textures.has(reference.substring(1)));
                if (def.has("uv")) {
                    assertEquals(4, def.getAsJsonArray("uv").size());
                    for (var coordinate : def.getAsJsonArray("uv")) {
                        double c = coordinate.getAsDouble();
                        assertTrue(Double.isFinite(c) && c >= 0 && c <= 16);
                    }
                }
            }
        }
    }

    @Test void inventoryAndBothHandsHaveVisibleTransforms() throws Exception {
        var display = json("models/item/debug_screwdriver.json").getAsJsonObject("display");
        for (var pose : new String[]{"gui", "ground", "fixed", "firstperson_righthand", "firstperson_lefthand",
                "thirdperson_righthand", "thirdperson_lefthand"}) {
            assertTrue(display.has(pose), pose);
            var transform = display.getAsJsonObject(pose);
            for (var key : new String[]{"rotation", "translation", "scale"}) {
                assertEquals(3, transform.getAsJsonArray(key).size());
                for (var n : transform.getAsJsonArray(key)) assertTrue(Double.isFinite(n.getAsDouble()));
            }
            for (var n : transform.getAsJsonArray("scale")) assertTrue(n.getAsDouble() >= .4 && n.getAsDouble() <= 1.5);
        }
    }

    @Test void bilingualCreativeOnlyInstructionsExistWithoutCraftingRecipe() throws Exception {
        for (var language : new String[]{"zh_cn", "en_us"}) {
            var messages = json("lang/" + language + ".json");
            for (var key : new String[]{"item.piq_fc_arcade.debug_screwdriver", "tooltip.piq_fc_arcade.debug_screwdriver"})
                assertFalse(messages.get(key).getAsString().isBlank());
        }
        assertNull(getClass().getResource("/data/piq_fc_arcade/recipe/debug_screwdriver.json"));
        assertNull(getClass().getResource("/data/piq_fc_arcade/recipes/debug_screwdriver.json"));
    }
}
