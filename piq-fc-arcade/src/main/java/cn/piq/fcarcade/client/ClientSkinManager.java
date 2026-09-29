package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.SkinDownloadChunkPayload;
import cn.piq.fcarcade.SkinDownloadStartPayload;
import cn.piq.fcarcade.SkinLibraryPayload;
import cn.piq.fcarcade.SkinUploadChunkPayload;
import cn.piq.fcarcade.SkinUploadStartPayload;
import cn.piq.fcarcade.skin.SkinImageCodec;
import cn.piq.fcarcade.skin.SkinLayout;
import cn.piq.fcarcade.skin.SkinTextureCache;
import cn.piq.fcarcade.skin.SkinTransferBuffer;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ClientSkinManager {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();
    private static final SkinTextureCache<ResourceLocation> TEXTURES = new SkinTextureCache<>(
            SkinTransferLimits.MAX_TEXTURES, ClientSkinManager::releaseTexture);
    private static final Set<String> INCOMPATIBLE = new HashSet<>();
    private static final Set<String> REJECTED_CACHE = new HashSet<>();
    private static final LinkedHashSet<String> QUEUED = new LinkedHashSet<>();
    private static final Map<String, Long> RETRY_AFTER = new LinkedHashMap<>();
    private static final int MAX_KNOWN_FAILURES = 512;
    private static final long DOWNLOAD_TIMEOUT_NANOS = 15_000_000_000L;

    private static Upload upload;
    private static SkinTransferBuffer download;
    private static String requestedHash;
    private static long lastDownloadActivity;
    private static int lastUploadPlayerTick = Integer.MIN_VALUE;

    private ClientSkinManager() {}

    static void clearOnDisconnect() {
        upload = null;
        download = null;
        requestedHash = null;
        lastUploadPlayerTick = Integer.MIN_VALUE;
        QUEUED.clear();
        INCOMPATIBLE.clear();
        REJECTED_CACHE.clear();
        RETRY_AFTER.clear();
        TEXTURES.clear();
    }

    private static void releaseTexture(ResourceLocation texture) {
        try { MINECRAFT.getTextureManager().release(texture); }
        catch (RuntimeException error) { FcArcadeMod.LOGGER.warn("[PIQ FC] 清理皮肤纹理失败", error); }
    }

    static void openLibrary(SkinLibraryPayload payload) {
        payload.serverSkins().stream().filter(skin -> !skin.compatible())
                .limit(MAX_KNOWN_FAILURES - INCOMPATIBLE.size()).forEach(skin -> INCOMPATIBLE.add(skin.sha256()));
        try { MINECRAFT.setScreen(new SkinLibraryScreen(payload, ClientSkinLibrary.list())); }
        catch (IOException error) { fail("读取本地皮肤文件夹失败", error); }
    }

    static void upload(BlockPos blockPos, ClientSkinLibrary.LocalSkin local) {
        if (MINECRAFT.getConnection() == null) return;
        if (upload != null) {
            overlay(Component.translatable("message.piq_fc_arcade.skin_transfer_busy"));
            return;
        }
        if (!local.compatible()) {
            overlay(Component.translatable("message.piq_fc_arcade.skin_incompatible_preserved"));
            return;
        }
        try {
            SkinImageCodec.Prepared prepared = ClientSkinLibrary.prepare(local);
            byte[] png = prepared.png();
            upload = new Upload(prepared.sha256(), png);
            FcNetwork.beginSkinUpload(new SkinUploadStartPayload(blockPos, local.name(), prepared.sha256(), png.length));
            MINECRAFT.setScreen(null);
            overlay(Component.translatable("message.piq_fc_arcade.skin_uploading", local.name()));
        } catch (IOException | IllegalArgumentException error) { fail("准备皮肤上传失败", error); }
    }

    static ResourceLocation textureFor(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || INCOMPATIBLE.contains(sha256)) return null;
        ResourceLocation loaded = TEXTURES.get(sha256);
        if (loaded != null) return loaded;
        if (!ClientFcDirectories.skinCacheReady()) return null;
        long now = System.nanoTime();
        if (now < RETRY_AFTER.getOrDefault(sha256, Long.MIN_VALUE)
                || INCOMPATIBLE.size() >= MAX_KNOWN_FAILURES) return null;
        if (!REJECTED_CACHE.contains(sha256)) {
            try {
                var path = ClientSkinLibrary.cachedPath(sha256);
                if (Files.isRegularFile(path)) {
                    byte[] png = SkinImageCodec.readBounded(path);
                    if (!SkinImageCodec.validateStored(png, sha256).compatible()) {
                        INCOMPATIBLE.add(sha256); // Preserve old bytes and NBT, with no per-frame reads/re-downloads.
                        return null;
                    }
                    return registerTexture(sha256, png);
                }
            } catch (IOException | RuntimeException error) {
                if (REJECTED_CACHE.size() < MAX_KNOWN_FAILURES && REJECTED_CACHE.add(sha256)) {
                    FcArcadeMod.LOGGER.warn("[PIQ FC] 本地皮肤缓存不可用，将重新下载 {}", sha256, error);
                }
            }
        }
        if (MINECRAFT.getConnection() != null && !sha256.equals(requestedHash) && QUEUED.size() < 32) QUEUED.add(sha256);
        return null;
    }

    static void startDownload(SkinDownloadStartPayload payload) {
        if (!payload.sha256().equals(requestedHash) || download != null) return;
        download = new SkinTransferBuffer(payload.name(), payload.sha256(), payload.totalBytes());
        lastDownloadActivity = System.nanoTime();
    }

    static void acceptDownload(SkinDownloadChunkPayload payload) {
        if (download == null || !download.sha256().equals(payload.sha256())) return;
        try {
            download.append(payload.offset(), payload.data());
            lastDownloadActivity = System.nanoTime();
            if (!download.complete()) return;
            String sha256 = download.sha256();
            byte[] png = download.completedBytes();
            SkinLayout layout = SkinImageCodec.validateStored(png, sha256);
            ClientSkinLibrary.storeCache(sha256, png);
            if (layout.compatible()) registerTexture(sha256, png);
            else if (INCOMPATIBLE.size() < MAX_KNOWN_FAILURES) INCOMPATIBLE.add(sha256);
            REJECTED_CACHE.remove(sha256);
            download = null;
            requestedHash = null;
            if (layout.compatible()) overlay(Component.translatable("message.piq_fc_arcade.skin_downloaded"));
        } catch (IOException | RuntimeException error) {
            String failed = download == null ? requestedHash : download.sha256();
            download = null;
            requestedHash = null;
            if (failed != null) defer(failed, 30_000_000_000L);
            fail("皮肤下载失败", error);
        }
    }

    static void update() {
        if (MINECRAFT.getConnection() == null) return;
        long now = System.nanoTime();
        if (requestedHash != null && now - lastDownloadActivity > DOWNLOAD_TIMEOUT_NANOS) {
            defer(requestedHash, 30_000_000_000L);
            requestedHash = null;
            download = null;
        }
        if (requestedHash == null && !QUEUED.isEmpty()) {
            var iterator = QUEUED.iterator();
            String next = iterator.next();
            iterator.remove();
            if (!INCOMPATIBLE.contains(next) && now >= RETRY_AFTER.getOrDefault(next, Long.MIN_VALUE)) {
                requestedHash = next;
                lastDownloadActivity = now;
                FcNetwork.requestSkinDownload(next);
            }
        }
        if (upload == null || MINECRAFT.player == null) return;
        int tick = MINECRAFT.player.tickCount;
        if (tick == lastUploadPlayerTick) return;
        lastUploadPlayerTick = tick;
        for (int chunk = 0; chunk < SkinTransferLimits.CHUNKS_PER_TICK && upload.offset < upload.png.length; chunk++) {
            int end = Math.min(upload.offset + SkinTransferLimits.CHUNK_BYTES, upload.png.length);
            FcNetwork.uploadSkinChunk(new SkinUploadChunkPayload(upload.sha256, upload.offset,
                    Arrays.copyOfRange(upload.png, upload.offset, end)));
            upload.offset = end;
        }
        overlay(Component.translatable("message.piq_fc_arcade.skin_upload_progress", upload.offset * 100 / upload.png.length));
        if (upload.offset >= upload.png.length) upload = null;
    }

    private static void defer(String hash, long nanos) {
        if (RETRY_AFTER.size() >= MAX_KNOWN_FAILURES && !RETRY_AFTER.containsKey(hash)) {
            RETRY_AFTER.remove(RETRY_AFTER.keySet().iterator().next());
        }
        RETRY_AFTER.put(hash, System.nanoTime() + nanos);
    }

    private static ResourceLocation registerTexture(String sha256, byte[] png) throws IOException {
        if (!SkinImageCodec.inspectHeader(png).compatible()) return null;
        ResourceLocation existing = TEXTURES.get(sha256);
        if (existing != null) return existing;
        String evicted = TEXTURES.evictIfFull();
        if (evicted != null) defer(evicted, 10_000_000_000L); // Avoid a ninth visible skin causing per-frame eviction churn.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "dynamic/skin/" + sha256);
        NativeImage image;
        try (var input = new java.io.ByteArrayInputStream(png)) { image = NativeImage.read(input); }
        DynamicTexture texture = null;
        try {
            if (image.getWidth() != SkinTransferLimits.TARGET_SIZE || image.getHeight() != SkinTransferLimits.TARGET_SIZE) {
                throw new IOException("皮肤纹理尺寸与火箭车 UV 不一致");
            }
            texture = new DynamicTexture(image);
            MINECRAFT.getTextureManager().register(id, texture);
            TEXTURES.put(sha256, id);
            return id;
        } catch (IOException | RuntimeException error) {
            if (texture != null) texture.close(); else image.close();
            throw error;
        }
    }

    private static void overlay(Component component) { MINECRAFT.gui.setOverlayMessage(component, false); }
    private static void fail(String context, Throwable error) {
        cn.piq.fcarcade.client.ui.DeviceNotices.record("机身皮肤",context,error);
    }
    private static final class Upload {
        private final String sha256;
        private final byte[] png;
        private int offset;
        private Upload(String sha256, byte[] png) { this.sha256 = sha256; this.png = png; }
    }
}
