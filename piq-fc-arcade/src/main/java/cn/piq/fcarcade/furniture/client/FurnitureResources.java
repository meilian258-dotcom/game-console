package cn.piq.fcarcade.furniture.client;

import cn.piq.fcarcade.furniture.WoodSpecies;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Furniture-only resource snapshot. Nothing is shared with the old whole-PNG hardware loaders. */
public final class FurnitureResources {
    private static final int MAX_MESH_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TRIANGLES = 30_000;
    public enum Shape { BENCH("bench"), OPEN("stool_open"), FOLDED("stool_folded");
        final String file;
        Shape(String file) { this.file = file; }
    }
    public enum Material { WOOD_SIDE("wood_side"), WOOD_END("wood_end"), CLOTH("cloth"), METAL("metal");
        final String key;
        Material(String key) { this.key = key; }
    }
    public record Part(Material material, float[] vertices) {}
    public record Materials(ResourceLocation side, ResourceLocation end) {
        public ResourceLocation sprite(Material material) {
            return switch (material) {
                case WOOD_SIDE -> side;
                case WOOD_END -> end;
                case CLOTH, METAL -> DETAILS;
            };
        }
    }
    public record Snapshot(Map<Shape, List<Part>> meshes, Map<WoodSpecies, Materials> materials) {
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
    }
    public static final ResourceLocation DETAILS = id("block/furniture/stool_details");

    private FurnitureResources() {}

    public static ResourceLocation meshId(Shape shape) { return id("meshes/furniture/" + shape.file + ".json"); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", path); }
    public static ResourceLocation textureFile(ResourceLocation sprite) {
        return ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
    }

    /** Only IDs are stored: the renderer obtains fresh atlas sprites each draw, including after F3+T. */
    public static Materials resolveMaterials(ResourceManager manager, WoodSpecies wood) {
        String suffix = switch (wood.id()) {
            case "crimson", "warped" -> "_stem";
            case "bamboo" -> "_block";
            default -> "_log";
        };
        var side = ResourceLocation.withDefaultNamespace("block/stripped_" + wood.id() + suffix);
        var end = ResourceLocation.withDefaultNamespace(side.getPath() + "_top");
        var planks = ResourceLocation.withDefaultNamespace("block/" + wood.id() + "_planks");
        // Missing pack resources do not reuse an earlier pack's image. Prefer the same species.
        side = existing(manager, side) ? side : existing(manager, planks) ? planks
                : ResourceLocation.withDefaultNamespace("block/oak_planks");
        end = existing(manager, end) ? end : side;
        return new Materials(side, end);
    }

    private static boolean existing(ResourceManager manager, ResourceLocation sprite) {
        return manager.getResource(textureFile(sprite)).isPresent();
    }

    /** Construct a complete replacement even if one model is bad; never retain old-pack geometry. */
    public static Snapshot load(ResourceManager manager, Consumer<String> diagnostic) {
        var meshes = new EnumMap<Shape, List<Part>>(Shape.class);
        for (Shape shape : Shape.values()) {
            try (var stream = manager.open(meshId(shape))) {
                byte[] bytes = stream.readNBytes(MAX_MESH_BYTES + 1);
                if (bytes.length > MAX_MESH_BYTES) throw new IllegalArgumentException("Mesh exceeds 4 MiB");
                meshes.put(shape, parse(bytes));
            } catch (Exception failure) {
                diagnostic.accept("Cannot load furniture " + shape.file + ": " + failure.getMessage());
            }
        }
        var materials = new EnumMap<WoodSpecies, Materials>(WoodSpecies.class);
        for (WoodSpecies wood : WoodSpecies.values()) materials.put(wood, resolveMaterials(manager, wood));
        return new Snapshot(Map.copyOf(meshes), Map.copyOf(materials));
    }

    static List<Part> parse(byte[] bytes) {
        var root = JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unknown furniture mesh version");
        var groups = root.getAsJsonObject("groups");
        var result = new ArrayList<Part>();
        int count = 0;
        for (Material material : Material.values()) {
            var triangles = groups.getAsJsonObject(material.key).getAsJsonArray("triangles");
            count += triangles.size();
            if (count > MAX_TRIANGLES) throw new IllegalArgumentException("Too many furniture triangles");
            float[] data = new float[triangles.size() * 24];
            int at = 0;
            for (var entry : triangles) {
                var triangle = entry.getAsJsonObject();
                var p = triple(triangle.getAsJsonArray("p"));
                var uv = triple(triangle.getAsJsonArray("uv"));
                var normal = triple(triangle.getAsJsonArray("n"));
                float nx = coordinate(normal, 0), ny = coordinate(normal, 1), nz = coordinate(normal, 2);
                float norm = nx * nx + ny * ny + nz * nz;
                if (Math.abs(norm - 1) > .002) throw new IllegalArgumentException("Non-unit furniture normal");
                int start = at;
                for (int v = 0; v < 3; v++) {
                    var point = triple(p.get(v).getAsJsonArray());
                    var tex = uv.get(v).getAsJsonArray();
                    if (tex.size() != 2) throw new IllegalArgumentException("Invalid furniture UV vector");
                    for (int axis = 0; axis < 3; axis++) data[at++] = coordinate(point, axis) / 16;
                    for (int axis = 0; axis < 2; axis++) {
                        float value = coordinate(tex, axis);
                        if (value < -.000001F || value > 1.000001F) throw new IllegalArgumentException("Furniture UV must be tile-split to 0..1");
                        data[at++] = Math.max(0, Math.min(1, value));
                    }
                    data[at++] = nx; data[at++] = ny; data[at++] = nz;
                }
                double ax = data[start+8]-data[start], ay = data[start+9]-data[start+1], az = data[start+10]-data[start+2];
                double bx = data[start+16]-data[start], by = data[start+17]-data[start+1], bz = data[start+18]-data[start+2];
                double winding = (ay*bz-az*by)*nx + (az*bx-ax*bz)*ny + (ax*by-ay*bx)*nz;
                if (!(winding > 0)) throw new IllegalArgumentException("Degenerate or inward furniture triangle");
            }
            result.add(new Part(material, data));
        }
        if (count == 0) throw new IllegalArgumentException("Empty furniture mesh");
        return List.copyOf(result);
    }

    private static JsonArray triple(JsonArray vector) {
        if (vector == null || vector.size() != 3) throw new IllegalArgumentException("Invalid furniture vector");
        return vector;
    }
    private static float coordinate(JsonArray vector, int at) {
        float value = vector.get(at).getAsFloat();
        if (!Float.isFinite(value) || Math.abs(value) > 64) throw new IllegalArgumentException("Invalid furniture coordinate");
        return value;
    }
}
