package cn.piq.fcarcade.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real resource transforms and cover-path contracts; not a live Minecraft render test. */
class CartridgeItemCoverRenderingTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/piq_fc_arcade/models");

    private record Point(double x, double y, double z) {
        Point add(Point v) { return new Point(x + v.x, y + v.y, z + v.z); }
        Point scale(double n) { return new Point(x * n, y * n, z * n); }
        double dot(Point v) { return x * v.x + y * v.y + z * v.z; }
    }

    private static JsonObject model(String name) throws Exception {
        return JsonParser.parseString(Files.readString(MODELS.resolve(name + ".json"))).getAsJsonObject();
    }

    private static Point vector(JsonObject object, String field, Point fallback) {
        if (!object.has(field)) return fallback;
        var values = object.getAsJsonArray(field);
        return new Point(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    private static Point rotate(Point point, Point degrees, boolean left) {
        // ItemTransform.apply: rotationXYZ(x, left ? -y : y, left ? -z : z).
        double z = Math.toRadians(left ? -degrees.z : degrees.z);
        double y = Math.toRadians(left ? -degrees.y : degrees.y);
        double x = Math.toRadians(degrees.x);
        Point rz = new Point(Math.cos(z) * point.x - Math.sin(z) * point.y,
                Math.sin(z) * point.x + Math.cos(z) * point.y, point.z);
        Point ry = new Point(Math.cos(y) * rz.x + Math.sin(y) * rz.z,
                rz.y, -Math.sin(y) * rz.x + Math.cos(y) * rz.z);
        return new Point(ry.x, Math.cos(x) * ry.y - Math.sin(x) * ry.z,
                Math.sin(x) * ry.y + Math.cos(x) * ry.z);
    }

    @Test void uploadedCoverIsOnlyOnTheNorthFrontAndShellKeepsThatExactUv() throws Exception {
        for (String name : List.of("block/home_fc_cartridge", "block/home_fc_cartridge_shell")) {
            var elements = model(name).getAsJsonArray("elements");
            var labels = java.util.stream.StreamSupport.stream(elements.spliterator(), false)
                    .map(value -> value.getAsJsonObject())
                    .filter(element -> element.get("name").getAsString().contains("中央游戏标签")).toList();
            assertEquals(1, labels.size());
            var faces = labels.getFirst().getAsJsonObject("faces");
            assertEquals(java.util.Set.of("north"), faces.keySet());
            assertEquals("[0.5,0.5,8.5,4.5]", faces.getAsJsonObject("north").get("uv").toString());
        }
    }

    @Test void bothFirstPersonHandsFaceTheCoverTowardTheCameraWithoutMirroringText() throws Exception {
        for (String name : List.of("item/fc_cartridge", "item/fc_cartridge_shell")) {
            JsonObject display = model(name).getAsJsonObject("display");
            Point rightNormal = null;
            for (boolean left : new boolean[]{false, true}) {
                var transform = display.getAsJsonObject(left ? "firstperson_lefthand" : "firstperson_righthand");
                Point rotation = vector(transform, "rotation", new Point(0, 0, 0));
                Point scale = vector(transform, "scale", new Point(1, 1, 1));
                Point translation = vector(transform, "translation", new Point(0, 0, 0));
                assertEquals(new Point(.8, .8, .8), scale);
                Point normal = rotate(new Point(0, 0, -1), rotation, left);
                Point up = rotate(new Point(0, 1, 0), rotation, left);
                // At rest vanilla's +45/-45 Y rotations cancel before item JSON.
                Point center = rotate(new Point(0, -3.75 / 16, -.75 / 16).scale(.8), rotation, left)
                        .add(new Point((left ? -1 : 1) * translation.x, translation.y, translation.z).scale(1 / 16.0))
                        .add(new Point(left ? -.56 : .56, -.52, -.72));
                assertTrue(normal.dot(center.scale(-1)) > .7, name + " front faces the camera in either hand");
                assertTrue(normal.z > .8);
                assertEquals(1, up.y, 1e-12);
                if (left) {
                    assertEquals(-rightNormal.x, normal.x, 1e-12);
                    assertEquals(rightNormal.z, normal.z, 1e-12);
                } else rightNormal = normal;
            }
        }
        // The previous -25 degree right-hand yaw faced the label away from the camera.
        assertTrue(rotate(new Point(0, 0, -1), new Point(0, -25, 0), false).z < -.8);
    }

    @Test void inventoryFramesAndGroundKeepTheirExistingTransformsAndBothThirdPersonHandsAgree() throws Exception {
        for (String name : List.of("item/fc_cartridge", "item/fc_cartridge_shell")) {
            JsonObject item = model(name), display = item.getAsJsonObject("display");
            assertEquals("builtin/entity", item.get("parent").getAsString());
            assertEquals(new Point(20, -155, 0), vector(display.getAsJsonObject("gui"), "rotation", null));
            assertEquals(new Point(0, -180, 0), vector(display.getAsJsonObject("fixed"), "rotation", null));
            assertEquals(new Point(.6, .6, .6), vector(display.getAsJsonObject("ground"), "scale", null));
            var right = display.getAsJsonObject("thirdperson_righthand");
            assertEquals(right, display.getAsJsonObject("thirdperson_lefthand"));
            assertEquals(new Point(0, 180, 0), vector(right, "rotation", null));
            assertEquals(new Point(0, 3, 1), vector(right, "translation", null));
            assertEquals(new Point(.7, .7, .7), vector(right, "scale", null));
        }
    }

    @Test void itemAndInsertedCardReuseTheSameHashBasedTextureAndBareBoardNeverRequestsACover() throws Exception {
        String renderer = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java"));
        String draw = renderer.substring(renderer.indexOf("private static void drawCartridge("), renderer.indexOf("static void drawQuads("));
        assertTrue(draw.indexOf("return; // Bare PCB") < draw.indexOf("ClientCartridgeCovers.texture(stack)"));
        assertTrue(draw.contains("FcCartridgeData.isShell(stack) ? SHELL_MODEL : CARD_MODEL"));
        assertTrue(renderer.contains("drawCartridge(cartridge, poses, buffers, light, overlay)"));
        String item = renderer.substring(renderer.indexOf("private static final class CartridgeItemRenderer"), renderer.indexOf("private static void drawController("));
        assertTrue(item.contains("drawCartridge(stack, poses, buffers, light, overlay)"));
        String cache = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientCartridgeCovers.java"));
        String texture = cache.substring(cache.indexOf("public static ResourceLocation texture("), cache.indexOf("public static void clearOnDisconnect("));
        assertTrue(texture.contains("FcCartridgeData.coverSha(stack)"));
        assertTrue(texture.contains("TEXTURES.get(hash)"));
        assertFalse(texture.contains("read(")); // No file decoding in any item/world render.
    }

    @Test void coverDownloadDoesNotRequireAnOpenEditorAndCommittedStackIsBroadcast() throws Exception {
        String server = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/server/ServerCartridgeService.java"));
        String handle = server.substring(server.indexOf("public static void handle("), server.indexOf("private static final class State"));
        assertTrue(handle.indexOf("CartridgeNetwork.DOWNLOAD_COVER") < handle.indexOf("state.sessions.get(player.getUUID())"));
        String commit = server.substring(server.indexOf("void commit("), server.indexOf("void setPlayers("));
        assertTrue(commit.contains("FcCartridgeData.write(session.stack, rom, name, cover)"));
        assertTrue(commit.contains("player.inventoryMenu.broadcastChanges()"));
        String download = server.substring(server.indexOf("void download("), server.indexOf("void downloadReply("));
        assertTrue(download.contains("CartridgeLimits.validHash(hash)"));
        assertTrue(download.contains("BUDGET.reserve("));
        assertTrue(download.contains("covers.read(hash)"));
        assertFalse(download.contains("hasCartridge"));
    }
}
