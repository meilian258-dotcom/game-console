package cn.piq.replaycompat.capture;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.api.ScreenVertex;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.system.MemoryUtil;

/** Client/render-thread only. No GL readback, compression, disk or network work. */
public final class CaptureHooks {
    private record Pending(RenderType type, ResourceLocation texture, CapturedVertices vertices, String sourceHint) {}
    private record Pixels(int width, int height, int[] abgr, long time, long nextFrame) {}
    private static final CaptureBudget BUDGET = new CaptureBudget();
    private static final Map<RenderType, ResourceLocation> MATERIALS = new IdentityHashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<Integer, Pixels> RAW_PIXELS = new LinkedHashMap<>();
    private static final Set<Integer> RAW_TEXTURES = new HashSet<>();
    private static final ThreadLocal<Integer> RAW_BOUND = ThreadLocal.withInitial(() -> -1);
    private static boolean world;
    private static double cameraX, cameraY, cameraZ;
    private static long timestamp;
    private static String dimension;
    private static Object recordedLevel;
    private static String sourceHint;
    private CaptureHooks() {}

    /** Call at HIGHEST priority before the existing AFTER_BLOCK_ENTITIES listeners. */
    public static void beginWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        world = false; sourceHint = null; PENDING.clear(); MATERIALS.clear();
        var level = Minecraft.getInstance().level;
        if (!ScreenReplay.recording() || ScreenReplay.playing() || level == null) { reset(); return; }
        long now = ScreenReplay.recordingTime();
        if (recordedLevel != level || now < timestamp) {
            closeExpired(Long.MAX_VALUE); BUDGET.clear();
            if (recordedLevel != null) RAW_PIXELS.clear();
        }
        recordedLevel = level;
        timestamp = now; dimension = level.dimension().location().toString();
        var camera = event.getCamera().getPosition();
        cameraX = camera.x; cameraY = camera.y; cameraZ = camera.z;
        closeExpired(now); world = true;
    }

    /** Call at LOWEST priority after existing devices drew; first-person/GUI is outside this scope. */
    public static void endWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || !world) return;
        flush(null); world = false; MATERIALS.clear();
    }

    public static void reset() {
        world = false; recordedLevel = null; dimension = null; sourceHint = null;
        PENDING.clear(); MATERIALS.clear(); RAW_PIXELS.clear(); BUDGET.clear(); RAW_BOUND.remove();
    }

    private static void closeExpired(long now) {
        for (String source : BUDGET.expired(now)) ScreenReplay.close(source, ScreenReplay.recordingTime());
    }

    public static void material(ResourceLocation texture, RenderType type) {
        if (world && texture != null && type != null && MATERIALS.size() < 128) MATERIALS.put(type, texture);
    }

    /** The physical block identity also stays stable when Sable moves a TV in world space. */
    public static void anchor(BlockPos pos) {
        sourceHint = !world || pos == null ? null : "legacy-block/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ();
    }

    public static void clearAnchor() { sourceHint = null; }

    public static VertexConsumer wrap(RenderType type, VertexConsumer original) {
        ResourceLocation texture = MATERIALS.get(type);
        if (!world || texture == null || PENDING.size() >= CaptureBudget.MAX_SCREENS) return original;
        var vertices = new CapturedVertices(original, cameraX, cameraY, cameraZ);
        PENDING.add(new Pending(type, texture, vertices, sourceHint));
        sourceHint = null;
        return vertices;
    }

    public static void flush(RenderType type) {
        if (!world) return;
        var ready = new ArrayList<Pending>();
        PENDING.removeIf(p -> { if (type == null || p.type == type) { ready.add(p); return true; } return false; });
        for (Pending pending : ready) {
            try {
                List<ScreenVertex> vertices = pending.vertices.finish();
                if (vertices.isEmpty()) continue;
                double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
                for (var vertex : vertices) {
                    minX=Math.min(minX,vertex.x()); minY=Math.min(minY,vertex.y()); minZ=Math.min(minZ,vertex.z());
                    maxX=Math.max(maxX,vertex.x()); maxY=Math.max(maxY,vertex.y()); maxZ=Math.max(maxZ,vertex.z());
                }
                String source = dimension + "/" + (pending.sourceHint != null ? pending.sourceHint
                        : CaptureBudget.source((minX+maxX)/2,(minY+maxY)/2,(minZ+maxZ)/2));
                if (!BUDGET.admit(source,timestamp)) continue;
                var texture = Minecraft.getInstance().getTextureManager().getTexture(pending.texture);
                if (!(texture instanceof DynamicTexture dynamic)) continue;
                Pixels pixels = RAW_PIXELS.get(dynamic.getId());
                // Computer uploaders bypass NativeImage; its pixels are not an image fallback.
                if (pixels == null && !RAW_TEXTURES.contains(dynamic.getId())) pixels = read(dynamic.getPixels());
                if (pixels != null) ScreenReplay.submit(new ScreenFrame(source,dimension,timestamp,pixels.width,pixels.height,pixels.abgr,vertices));
            } catch (RuntimeException ignored) {
                // A texture released/reloaded mid-frame drops that frame, not the device's original draw.
            }
        }
    }

    private static Pixels read(NativeImage image) {
        if (image == null || image.format() != NativeImage.Format.RGBA
                || !CaptureBudget.validPixels(image.getWidth(),image.getHeight())) return null;
        // NativeImage's bulk ABGR copy avoids a checked native accessor for every pixel.
        // Do not cache mutable NativeImage identities: another draw may change their pixels.
        return new Pixels(image.getWidth(),image.getHeight(),image.getPixelsRGBA(),timestamp,timestamp);
    }

    /** Only called by two pinned computer upload sites, never a global texture observer. */
    public static void rawBind(int texture) {
        RAW_BOUND.set(texture);
        if (RAW_TEXTURES.size() < 128) RAW_TEXTURES.add(texture);
    }

    public static void releaseRaw(int texture) { RAW_PIXELS.remove(texture); RAW_TEXTURES.remove(texture); }

    public static void rawUpload(int width, int height, long pointer) {
        if (!ScreenReplay.recording() || ScreenReplay.playing() || pointer == 0 || !CaptureBudget.validPixels(width,height)) return;
        int texture=RAW_BOUND.get();
        if (texture < 0) return;
        long now=ScreenReplay.recordingTime();
        Pixels old=RAW_PIXELS.get(texture);
        if (old!=null && now>=old.time && now<old.nextFrame) return;
        if (old==null && RAW_PIXELS.size()>=CaptureBudget.MAX_SCREENS) RAW_PIXELS.remove(RAW_PIXELS.keySet().iterator().next());
        int[] pixels=new int[width*height];
        // Existing application-owned RGBA bytes, before the unchanged upload. Not glReadPixels.
        MemoryUtil.memByteBuffer(pointer,pixels.length*4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        RAW_PIXELS.put(texture,new Pixels(width,height,pixels,now,
                CaptureBudget.nextFrame(now,old==null||now<old.time?now:old.nextFrame)));
    }
}
