package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Free Blockbench triangles retain their original UVs; prepared once per resource reload. */
final class SuborHardwareMesh {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "meshes/home_subor_sb926.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "textures/block/home_subor_sb926.png");
    private static final String[] GROUPS = {"body", "p1_docked", "p2_docked", "p1_held", "p2_held"};
    private static final ResourceLocation WIDE_MODEL = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "meshes/home_subor_sb926_wide.json");
    private static final ResourceLocation COMPACT_MODEL = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "meshes/home_subor_sb926_compact.json");
    private static final String[] WIDE_GROUPS = {"body", "p1_docked", "p2_docked", "p1_held", "p2_held", "lid_closed", "lid_open"};
    private static volatile Map<String, float[]> meshes = Map.of();
    private static volatile Map<String, float[]> wideMeshes = Map.of();
    private static volatile Map<String, float[]> compactMeshes = Map.of();
    private static volatile Map<String,float[][]> heldParts=Map.of();

    private SuborHardwareMesh() {}

    static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) SuborHardwareMesh::reload);
    }

    private static void reload(ResourceManager manager) {
        meshes = load(manager, MODEL, GROUPS);
        wideMeshes = load(manager, WIDE_MODEL, WIDE_GROUPS);
        compactMeshes = load(manager, COMPACT_MODEL, WIDE_GROUPS);
        var next=new HashMap<String,float[][]>();
        for(String group:new String[]{"p1_held","p2_held"}) {
            float[] data=meshes.get(group);
            if(data!=null)next.put(group,ControllerButtonRenderer.partitionSubor(data));
        }
        heldParts=Map.copyOf(next);
    }

    private static Map<String, float[]> load(ResourceManager manager, ResourceLocation model, String[] names) {
        Map<String, float[]> next = new HashMap<>();
        try (Reader reader = manager.openAsReader(model)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unknown SB926 mesh version");
            JsonObject groups = root.getAsJsonObject("groups");
            for (String name : names) {
                var triangles = groups.getAsJsonObject(name).getAsJsonArray("triangles");
                if (triangles.isEmpty() || triangles.size() > 12000) throw new IllegalArgumentException("SB926 triangle count");
                float[] data = new float[triangles.size() * 3 * 8];
                int at = 0;
                for (var entry : triangles) {
                    var triangle = entry.getAsJsonObject();
                    var p = triangle.getAsJsonArray("p");
                    var uv = triangle.getAsJsonArray("uv");
                    var normal = triangle.getAsJsonArray("n");
                    for (int vertex = 0; vertex < 3; vertex++) {
                        for (int axis = 0; axis < 3; axis++) data[at++] = finite(p.get(vertex).getAsJsonArray().get(axis).getAsFloat()) / 16;
                        for (int axis = 0; axis < 2; axis++) data[at++] = finite(uv.get(vertex).getAsJsonArray().get(axis).getAsFloat());
                        for (int axis = 0; axis < 3; axis++) data[at++] = finite(normal.get(axis).getAsFloat());
                    }
                }
                next.put(name, data);
            }
            return Map.copyOf(next);
        } catch (Exception exception) {
            // Never retain a prior pack's mesh/UVs after a failed reload.
            com.mojang.logging.LogUtils.getLogger().error("Cannot load SB926 hardware mesh", exception);
            return Map.of();
        }
    }

    private static float finite(float number) {
        if (!Float.isFinite(number) || Math.abs(number) > 1024) throw new IllegalArgumentException("Invalid SB926 vertex");
        return number;
    }

    static void draw(String group, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        drawData(meshes.get(group), poses, buffers, light, overlay);
    }

    static void drawWide(String group, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        drawData(wideMeshes.get(group), poses, buffers, light, overlay);
    }
    static void drawWide(String group, boolean compact, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        drawData((compact ? compactMeshes : wideMeshes).get(group), poses, buffers, light, overlay);
    }
    static void drawHeld(String group,net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.ItemDisplayContext context,
                         PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        float[][] parts=heldParts.get(group);
        if(parts==null){draw(group,poses,buffers,light,overlay);return;}
        var animation=ClientControllerAnimation.state(stack,context);
        for(int part=0;part<parts.length;part++) {
            poses.pushPose();
            try {
                if(ControllerButtonRenderer.moving(part,animation))ControllerButtonRenderer.apply(part,animation,true,poses);
                drawData(parts[part],poses,buffers,light,overlay);
            } finally { poses.popPose(); }
        }
    }

    private static void drawData(float[] data, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (data == null) return;
        VertexConsumer target = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose pose = poses.last();
        for (int triangle = 0; triangle < data.length; triangle += 24) {
            // entityCutoutNoCull uses QUADS. A degenerate fourth vertex preserves each source triangle.
            for (int vertex = 0; vertex < 4; vertex++) {
                int at = triangle + Math.min(vertex, 2) * 8;
                target.addVertex(pose, data[at], data[at+1], data[at+2]).setColor(255,255,255,255)
                        .setUv(data[at+3], data[at+4]).setOverlay(overlay).setLight(light)
                        .setNormal(pose, data[at+5], data[at+6], data[at+7]);
            }
        }
    }
}
