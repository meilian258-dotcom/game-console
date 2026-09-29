package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import cn.piq.fcarcade.storage.FcStoragePaths;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Client-thread API. Rendering only does bounded map/queue work; file/decode/composition is background. */
public final class ClientCartridgeCovers {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation BASE = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,
            "textures/block/home_fc_cartridge_skin.png");
    private static final Map<String, ResourceLocation> TEXTURES = new LinkedHashMap<>(16, .75f, true);
    private static final LinkedHashSet<String> QUEUED = new LinkedHashSet<>();
    private static final Map<String, Long> RETRY = new LinkedHashMap<>();
    private static final CartridgeCoverRepository CACHE = new CartridgeCoverRepository(
            ClientFcDirectories.path(FcStoragePaths.Area.COVER_CACHE));
    private static int generation;
    private static String requested;
    private static CartridgeTransfer download;
    private static long activity;
    private static Resource baseResource;
    private ClientCartridgeCovers() {}
    public static ResourceLocation texture(ItemStack stack) {
        String hash = FcCartridgeData.coverSha(stack);
        if (hash.isEmpty()) return null;
        ResourceLocation found = TEXTURES.get(hash);
        if (found != null) return found;
        if (MC.getConnection() != null && !hash.equals(requested) && QUEUED.size() < 32
                && System.nanoTime() >= RETRY.getOrDefault(hash, Long.MIN_VALUE)) QUEUED.add(hash);
        return null;
    }
    public static void clearOnDisconnect() {
        generation++; requested = null; download = null; baseResource = null; QUEUED.clear(); RETRY.clear();
        for (ResourceLocation resource : TEXTURES.values()) MC.getTextureManager().release(resource);
        TEXTURES.clear();
    }
    static void update() {
        if (MC.getConnection() == null) {
            if (requested != null || !TEXTURES.isEmpty() || !QUEUED.isEmpty()) clearOnDisconnect();
            return;
        }
        if (requested != null && System.nanoTime() - activity > 90_000_000_000L) fail(requested);
        if (requested != null || QUEUED.isEmpty()) return;
        var iterator = QUEUED.iterator(); String hash = iterator.next(); iterator.remove();
        if (TEXTURES.containsKey(hash) || System.nanoTime() < RETRY.getOrDefault(hash, Long.MIN_VALUE)) return;
        requested = hash; activity = System.nanoTime(); int epoch = generation;
        baseResource = MC.getResourceManager().getResource(BASE).orElse(null);
        Resource resource = baseResource;
        if (resource == null) { fail(hash); return; }
        if (!ClientCartridgeIo.submit(() -> {
            byte[] cached = null;
            try {
                ClientFcDirectories.prepare(FcStoragePaths.Area.COVER_CACHE);
                if (CACHE.exists(hash)) cached = CACHE.read(hash);
            } catch (IOException error) {
                FcArcadeMod.LOGGER.warn("[PIQ FC] 卡带封面缓存不可用，旧数据保留", error);
            }
            if (cached != null) compose(hash, cached, resource, epoch);
            else MC.execute(() -> {
                if (!current(hash, epoch)) return;
                activity = System.nanoTime();
                CartridgeNetwork.send(new CartridgeNetwork.Request(CartridgeNetwork.DOWNLOAD_COVER,
                        CartridgeNetwork.NO_TARGET, hash, "", "", "", 0, 0, new byte[0]));
            });
        })) fail(hash);
    }
    public static void receive(CartridgeNetwork.Reply reply) {
        if (MC.getConnection() == null || requested == null || !requested.equals(reply.hash())) return;
        try {
            if (reply.operation() == CartridgeNetwork.COVER_START) {
                if (reply.total() <= 0 || reply.total() > CartridgeLimits.MAX_COVER_BYTES || download != null) { fail(reply.hash()); return; }
                download = new CartridgeTransfer(reply.total(), System.nanoTime()); activity = System.nanoTime(); return;
            }
            if (download == null || reply.total() != download.total()) return;
            download.append(reply.offset(), reply.data()); activity = System.nanoTime();
            if (download.received() != download.total()) return;
            byte[] bytes = download.finish(); download = null;
            int epoch = generation; String hash = requested; Resource resource = baseResource;
            if (!ClientCartridgeIo.submit(() -> {
                try {
                    CartridgeCoverCodec.validate(bytes, hash);
                    ClientFcDirectories.prepare(FcStoragePaths.Area.COVER_CACHE);
                    CACHE.store(hash, bytes);
                }
                catch (IOException error) { /* A full/unwritable disk cache must not prevent a valid in-memory cover. */ }
                compose(hash, bytes, resource, epoch);
            })) fail(hash);
        } catch (RuntimeException error) { fail(reply.hash()); }
    }
    private static void compose(String hash, byte[] cover, Resource resource, int epoch) {
        NativeImage image = null;
        try {
            byte[] base;
            try (var input = resource.open()) { base = input.readNBytes(CartridgeLimits.MAX_SOURCE_COVER_BYTES + 1); }
            byte[] composite = CartridgeCoverCodec.compose(base, cover, hash);
            image = NativeImage.read(new ByteArrayInputStream(composite));
            NativeImage result = image;
            MC.execute(() -> {
                if (!current(hash, epoch)) { result.close(); return; }
                DynamicTexture texture = null;
                try {
                    if (result.getWidth() != 1024 || result.getHeight() != 1024) throw new IllegalArgumentException("合成卡带贴图尺寸无效");
                    if (TEXTURES.size() >= 16) {
                        var oldest = TEXTURES.entrySet().iterator(); var entry = oldest.next(); oldest.remove();
                        MC.getTextureManager().release(entry.getValue()); defer(entry.getKey(), 10_000_000_000L);
                    }
                    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "dynamic/cartridge/" + hash);
                    texture = new DynamicTexture(result);
                    MC.getTextureManager().register(location, texture);
                    TEXTURES.put(hash, location); requested = null; baseResource = null;
                } catch (RuntimeException error) {
                    if (texture != null) texture.close(); else result.close();
                    fail(hash);
                }
            });
        } catch (IOException | RuntimeException error) {
            if (image != null) image.close();
            MC.execute(() -> { if (current(hash, epoch)) fail(hash); });
        }
    }
    private static boolean current(String hash, int epoch) {
        return epoch == generation && hash.equals(requested) && MC.getConnection() != null;
    }
    private static void fail(String hash) {
        defer(hash, 15_000_000_000L);
        if (hash.equals(requested)) { requested = null; download = null; baseResource = null; }
    }
    private static void defer(String hash, long delay) {
        if (RETRY.size() >= 128 && !RETRY.containsKey(hash)) RETRY.remove(RETRY.keySet().iterator().next());
        RETRY.put(hash, System.nanoTime() + delay);
    }
}
