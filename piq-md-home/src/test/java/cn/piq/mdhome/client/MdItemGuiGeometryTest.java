// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MC parser, element rotation and item transforms, without a GL/client session. */
class MdItemGuiGeometryTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static String resource(String name) throws Exception {
        try (var input = MdItemGuiGeometryTest.class.getResourceAsStream(
                "/assets/piq_md_home/models/" + name + ".json")) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BlockModel model(String name) throws Exception {
        return BlockModel.fromString(resource(name));
    }

    private static BlockModel consoleItem() throws Exception {
        var item = model("item/md2");
        assertEquals("piq_md_home:block/md2_empty", item.getParentLocation().toString());
        item.parent = model("block/md2_empty");
        return item;
    }

    private static PoseStack guiPose(ItemTransform transform) {
        var poses = new PoseStack();
        // GuiGraphics.renderItem: center of a 16px slot, Y-down screen coordinates.
        poses.translate(8, 8, 150);
        poses.scale(16, -16, 16);
        // ItemRenderer.render: apply the item's context transform BEFORE recentering.
        transform.apply(false, poses);
        poses.translate(-.5f, -.5f, -.5f);
        return poses;
    }

    private static List<Vector3f> vertices(BlockModel model) throws Exception {
        // Reuse FaceBakery's exact rotation/rescale math, including oblique keycaps.
        // No fake TextureAtlasSprite or GL context is needed for vertex projection.
        Method rotate = FaceBakery.class.getDeclaredMethod(
                "applyElementRotation", Vector3f.class, BlockElementRotation.class);
        rotate.setAccessible(true);
        var bakery = new FaceBakery();
        var vertices = new ArrayList<Vector3f>();
        for (var element : model.getElements()) {
            for (var face : element.faces.keySet()) {
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    int edge = switch (face.getAxis()) { case X -> x; case Y -> y; case Z -> z; };
                    if (edge != (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0)) continue;
                    var vertex = new Vector3f(x == 0 ? element.from.x : element.to.x,
                            y == 0 ? element.from.y : element.to.y,
                            z == 0 ? element.from.z : element.to.z).div(16);
                    rotate.invoke(bakery, vertex, element.rotation);
                    vertices.add(vertex);
                }
            }
        }
        assertFalse(vertices.isEmpty());
        return vertices;
    }

    private static float[] bounds(BlockModel model, ItemTransform transform) throws Exception {
        var pose = guiPose(transform).last().pose();
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var vertex : vertices(model)) {
            var screen = pose.transformPosition(vertex);
            bounds[0] = Math.min(bounds[0], screen.x); bounds[1] = Math.min(bounds[1], screen.y);
            bounds[2] = Math.max(bounds[2], screen.x); bounds[3] = Math.max(bounds[3], screen.y);
        }
        return bounds;
    }

    @Test void consoleGuiFitsSlotAndStaysCenteredAtEveryGuiScale() throws Exception {
        var item = consoleItem();
        float[] bounds = bounds(item, item.getTransforms().getTransform(ItemDisplayContext.GUI));
        for (int scale = 1; scale <= 4; scale++) {
            assertTrue(bounds[0] * scale >= scale && bounds[1] * scale >= scale);
            assertTrue(bounds[2] * scale <= 15 * scale && bounds[3] * scale <= 15 * scale);
        }
        assertEquals(8, (bounds[0] + bounds[2]) / 2, .02);
        assertEquals(8, (bounds[1] + bounds[3]) / 2, .02);
        assertTrue(bounds[2] - bounds[0] > 13, "retain useful icon size, do not merely shrink to nothing");
        assertTrue(bounds[3] - bounds[1] > 5.5);
        // Mutation control: the world model's old inherited GUI really overflows.
        float[] old = bounds(item, item.parent.getTransforms().getTransform(ItemDisplayContext.GUI));
        assertTrue(old[3] > 16.5, "old icon must reproduce the reported bottom overflow");
    }

    @Test void consoleOverrideCannotChangeOtherDisplayContextsOrWorldGeometry() throws Exception {
        var item = consoleItem();
        assertSame(item.parent.getElements(), item.getElements());
        for (var context : ItemDisplayContext.values()) {
            if (context == ItemDisplayContext.GUI) continue;
            assertSame(item.parent.getTransforms().getTransform(context), item.getTransforms().getTransform(context),
                    "only GUI may override inherited display: " + context);
        }
        var json = JsonParser.parseString(resource("item/md2")).getAsJsonObject();
        assertEquals(java.util.Set.of("parent", "display"), json.keySet());
        assertEquals(java.util.Set.of("gui"), json.getAsJsonObject("display").keySet());
    }

    private static Vector3f direction(ItemTransform transform, float x, float y, float z) {
        var poses = new PoseStack(); transform.apply(false, poses);
        return poses.last().pose().transformDirection(new Vector3f(x, y, z)).normalize();
    }

    @Test void cartridgeGuiFacesActualNorthLabelTowardViewerWithoutMirroring() throws Exception {
        var stub = model("item/md_cartridge");
        var mesh = model("item/md_cartridge_mesh");
        var gui = stub.getTransforms().getTransform(ItemDisplayContext.GUI);
        var meshGui = mesh.getTransforms().getTransform(ItemDisplayContext.GUI);
        assertEquals(gui, meshGui); assertEquals(gui.rightRotation, meshGui.rightRotation);
        assertEquals("minecraft:builtin/entity", stub.getParentLocation().toString());
        assertEquals("item/md_cartridge_mesh", MdCartridgeRenderer.MODEL.id().getPath());
        var rawElements = JsonParser.parseString(resource("item/md_cartridge_mesh"))
                .getAsJsonObject().getAsJsonArray("elements");
        int labelIndex = -1;
        for (int i = 0; i < rawElements.size(); i++) {
            if (rawElements.get(i).getAsJsonObject().get("name").getAsString().equals("游戏标签正面")) labelIndex = i;
        }
        assertTrue(labelIndex >= 0);
        assertEquals(java.util.Set.of(Direction.NORTH), mesh.getElements().get(labelIndex).faces.keySet());
        var front = direction(gui, 0, 0, -1);
        var right = direction(gui, -1, 0, 0); // NORTH-face UV grows toward -X.
        var up = direction(gui, 0, 1, 0);
        assertTrue(front.z > .7f); assertTrue(right.x > .8f); assertTrue(up.y > .9f);
        assertTrue(right.x * up.y - right.y * up.x > .7f, "label must not be mirrored");
        var old = new ItemTransform(gui.rotation, gui.translation, gui.scale);
        assertTrue(direction(old, 0, 0, -1).z < -.7f, "old GUI must show the back");
        float[] bounds = bounds(mesh, gui);
        assertTrue(bounds[0] > 1 && bounds[1] > 1 && bounds[2] < 15 && bounds[3] < 15);
    }
}
