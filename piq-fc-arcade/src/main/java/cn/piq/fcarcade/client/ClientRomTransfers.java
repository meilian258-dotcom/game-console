package cn.piq.fcarcade.client;

import cn.piq.fcarcade.ArcadeJoinPromptPayload;
import cn.piq.fcarcade.ArcadeLibraryPayload;
import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.RomDownloadChunkPayload;
import cn.piq.fcarcade.RomDownloadStartPayload;
import cn.piq.fcarcade.RomUploadChunkPayload;
import cn.piq.fcarcade.RomUploadStartPayload;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomTransferBuffer;
import cn.piq.fcarcade.rom.RomTransferLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ClientRomTransfers {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();
    private static final Map<String, List<Consumer<RomDescriptor>>> AFTER_DOWNLOAD = new HashMap<>();
    private static final Set<String> CHECKING_LOCAL = new HashSet<>();
    private static final ArrayDeque<String> DOWNLOAD_QUEUE = new ArrayDeque<>();
    private static final Set<String> QUEUED_DOWNLOADS = new HashSet<>();

    private static Upload upload;
    private static RomTransferBuffer download;
    private static String requestedDownload = "";
    private static int lastUploadPlayerTick = Integer.MIN_VALUE;
    private static final Map<Long, ArcadeSessionPayload> EXPECTED_SESSIONS = new HashMap<>();
    private static long generation;
    private static boolean completingDownload;
    private static long lastDownloadActivityNanos;
    private static final Map<String, Integer> DOWNLOAD_ATTEMPTS = new HashMap<>();

    private ClientRomTransfers() {
    }

    static void openLibrary(ArcadeLibraryPayload payload) {
        submitIo(ClientRomLibrary::list, (roms, error) -> {
            if (error != null) {
                fail("读取本地 ROM 游戏库失败", error);
                return;
            }
            MINECRAFT.setScreen(new RomLibraryScreen(
                    payload.blockPos(),
                    payload.selectedSha256(),
                    payload.leaderboardEnabled(),
                    roms,
                    payload.serverRoms(), payload.capabilities()));
        });
    }

    static void join(ArcadeJoinPromptPayload payload) {
        ensureLocal(
                payload.romSha256(),
                local -> FcNetwork.requestJoin(payload.blockPos(), payload.romSha256()));
    }

    static boolean hasPendingParticipant(){return EXPECTED_SESSIONS.values().stream().anyMatch(p->p.active()&&(p.computeHost()||p.role().controllerIndex()>=0));}
    static int pendingNativeControlClaims(){
        return (int)EXPECTED_SESSIONS.values().stream().filter(p->p.active()&&(p.computeHost()||p.role().controllerIndex()>=0))
                .filter(p->{var state=NetplayClient.state(p.sessionId());return state!=null&&state.jniTrial()&&!NetplayClient.nativeSlotHeld(p.sessionId());}).limit(4).count();
    }
    static void applySession(ArcadeSessionPayload payload) {
        if(payload.active()&&(payload.computeHost()||payload.role().controllerIndex()>=0))cn.piq.fcarcade.client.watch.WatchClient.controlStarting(payload.sessionId());
        if(payload.active()&&(payload.computeHost()||payload.role().controllerIndex()>=0))PrivateHomeClient.stop("收到公开 FC 游戏会话");
        if (!payload.active()) {
            EXPECTED_SESSIONS.remove(payload.sessionId());
            ClientArcadeEvents.applySessionReady(payload);
            return;
        }
        EXPECTED_SESSIONS.put(payload.sessionId(), payload);
        ensureLocal(payload.romSha256(), local -> {
            if (EXPECTED_SESSIONS.get(payload.sessionId()) != payload) return;
            ClientArcadeEvents.applySessionReady(payload);
            // The emulator reports ROM readiness only after its background
            // instance is initialized and selected for local simulation.
        });
    }

    static void select(BlockPos blockPos, String sha256, boolean serverHasRom) {
        ensureLocal(sha256, local -> {
            if (serverHasRom) {
                FcNetwork.selectRom(blockPos, sha256);
                MINECRAFT.setScreen(null);
                return;
            }
            if (upload != null) {
                overlay(Component.translatable(
                        "message.piq_fc_arcade.rom_transfer_busy"));
                return;
            }
            byte[] bytes = local.bytes();
            upload = new Upload(local.sha256(), bytes);
            FcNetwork.beginRomUpload(new RomUploadStartPayload(
                    blockPos,
                    truncateFileName(local.fileName()),
                    local.sha256(),
                    bytes.length));
            MINECRAFT.setScreen(null);
            overlay(Component.translatable(
                    "message.piq_fc_arcade.rom_uploading",
                    local.fileName()));
        });
    }

    static void startDownload(RomDownloadStartPayload payload) {
        if (!payload.sha256().equals(requestedDownload)) return;
        lastDownloadActivityNanos = System.nanoTime();
        download = new RomTransferBuffer(
                payload.fileName(),
                payload.sha256(),
                payload.totalBytes());
        overlay(Component.translatable(
                "message.piq_fc_arcade.rom_downloading",
                payload.fileName()));
    }

    static void acceptDownload(RomDownloadChunkPayload payload) {
        if (download == null || completingDownload
                || !download.sha256().equals(payload.sha256())) return;
        String transferHash = download.sha256();
        lastDownloadActivityNanos = System.nanoTime();
        try {
            download.append(payload.offset(), payload.data());
            int received = download.receivedBytes();
            int total = download.totalBytes();
            if (!download.complete()) {
                overlay(Component.translatable(
                        "message.piq_fc_arcade.rom_download_progress",
                        received * 100 / total));
                return;
            }
            String sha256 = download.sha256();
            String fileName = download.fileName();
            RomTransferBuffer finished = download;
            completingDownload = true;
            submitIo(() -> ClientRomLibrary.storeDownloaded(
                    fileName, sha256, finished.completedBytes()), (stored, error) -> {
                download = null;
                completingDownload = false;
                requestedDownload = "";
                QUEUED_DOWNLOADS.remove(sha256);
                DOWNLOAD_ATTEMPTS.remove(sha256);
                if (error == null) {
                    overlay(Component.translatable(
                            "message.piq_fc_arcade.rom_downloaded", stored.fileName()));
                    runCallbacks(sha256, stored);
                } else {
                    AFTER_DOWNLOAD.remove(sha256);
                    fail("ROM 下载校验失败", error);
                }
                requestNextDownload();
            });
        } catch (Throwable error) {
            download = null;
            completingDownload = false;
            requestedDownload = "";
            QUEUED_DOWNLOADS.remove(transferHash);
            AFTER_DOWNLOAD.remove(transferHash);
            DOWNLOAD_ATTEMPTS.remove(transferHash);
            fail("ROM 下载校验失败", error);
            requestNextDownload();
        }
    }

    static void update() {
        if (MINECRAFT.getConnection() == null) {
            clearOnDisconnect();
            return;
        }
        tickDownloadTimeout();
        if (upload == null || MINECRAFT.player == null) return;
        int playerTick = MINECRAFT.player.tickCount;
        if (playerTick == lastUploadPlayerTick) return;
        lastUploadPlayerTick = playerTick;

        for (int chunk = 0;
             chunk < RomTransferLimits.CHUNKS_PER_TICK
                     && upload.offset < upload.bytes.length;
             chunk++) {
            int end = Math.min(
                    upload.offset + RomTransferLimits.CHUNK_BYTES,
                    upload.bytes.length);
            FcNetwork.uploadRomChunk(new RomUploadChunkPayload(
                    upload.sha256,
                    upload.offset,
                    Arrays.copyOfRange(upload.bytes, upload.offset, end)));
            upload.offset = end;
        }
        overlay(Component.translatable(
                "message.piq_fc_arcade.rom_upload_progress",
                upload.offset * 100 / upload.bytes.length));
        if (upload.offset >= upload.bytes.length) upload = null;
    }

    /** Reuse the hash-verified FC download queue without creating a gameplay session. Main thread only. */
    static Runnable observeContent(String sha256,Consumer<RomDescriptor> callback){
        ensureLocal(sha256,callback);
        return ()->{var list=AFTER_DOWNLOAD.get(sha256);if(list!=null){list.remove(callback);if(list.isEmpty())AFTER_DOWNLOAD.remove(sha256);}};
    }
    private static void ensureLocal(String sha256, Consumer<RomDescriptor> callback) {
        if (MINECRAFT.getConnection() == null) return;
        AFTER_DOWNLOAD.computeIfAbsent(sha256, ignored -> new ArrayList<>())
                .add(callback);
        if (QUEUED_DOWNLOADS.contains(sha256) || !CHECKING_LOCAL.add(sha256)) return;
        submitIo(() -> ClientRomLibrary.loadBySha256(sha256), (existing, error) -> {
            CHECKING_LOCAL.remove(sha256);
            if (existing != null) {
                runCallbacks(sha256, existing);
            } else if (error != null) {
                AFTER_DOWNLOAD.remove(sha256);
                fail("读取本地 ROM 失败", error);
            } else {
                if (QUEUED_DOWNLOADS.add(sha256)) DOWNLOAD_QUEUE.addLast(sha256);
                requestNextDownload();
            }
        });
    }

    private static void requestNextDownload() {
        if (MINECRAFT.getConnection() == null || download != null
                || completingDownload || !requestedDownload.isEmpty()) return;
        String next = DOWNLOAD_QUEUE.pollFirst();
        if (next == null) return;
        requestedDownload = next;
        lastDownloadActivityNanos = System.nanoTime();
        DOWNLOAD_ATTEMPTS.merge(next, 1, Integer::sum);
        FcNetwork.requestRomDownload(next);
    }

    private static void tickDownloadTimeout() {
        if (requestedDownload.isEmpty() || completingDownload) return;
        long limit = download == null ? 5_000_000_000L : 15_000_000_000L;
        if (System.nanoTime() - lastDownloadActivityNanos < limit) return;
        String expired = requestedDownload;
        requestedDownload = "";
        download = null;
        if (DOWNLOAD_ATTEMPTS.getOrDefault(expired, 0) < 3) {
            DOWNLOAD_QUEUE.addFirst(expired);
            overlay(Component.literal("FC 游戏下载暂未响应，正在重试…"));
        } else {
            DOWNLOAD_ATTEMPTS.remove(expired);
            QUEUED_DOWNLOADS.remove(expired);
            AFTER_DOWNLOAD.remove(expired);
            cn.piq.fcarcade.client.ui.DeviceNotices.record("FC","FC 游戏下载失败：服务器可能正在刷新游戏库，请稍后重新右键。");
        }
        requestNextDownload();
    }

    private static void runCallbacks(String sha256, RomDescriptor descriptor) {
        List<Consumer<RomDescriptor>> callbacks = AFTER_DOWNLOAD.remove(sha256);
        if (callbacks == null) return;
        for (Consumer<RomDescriptor> callback : callbacks) {
            try {
                callback.accept(descriptor);
            } catch (RuntimeException error) {
                fail("ROM 就绪后启动失败", error);
            }
        }
    }

    static void clearOnDisconnect() {
        generation++;
        upload = null;
        download = null;
        completingDownload = false;
        requestedDownload = "";
        lastUploadPlayerTick = Integer.MIN_VALUE;
        EXPECTED_SESSIONS.clear();
        CHECKING_LOCAL.clear();
        AFTER_DOWNLOAD.clear();
        DOWNLOAD_QUEUE.clear();
        QUEUED_DOWNLOADS.clear();
        DOWNLOAD_ATTEMPTS.clear();
        lastDownloadActivityNanos = 0;
    }

    private static <T> void submitIo(Callable<T> action, BiConsumer<T, Throwable> callback) {
        long token = generation;
        var connection = MINECRAFT.getConnection();
        if (connection == null) return;
        try {
            ClientIoExecutor.execute(() -> {
                T result = null;
                Throwable failure = null;
                try {
                    result = action.call();
                } catch (Exception error) {
                    failure = error;
                }
                T completed = result;
                Throwable error = failure;
                MINECRAFT.execute(() -> {
                    if (generation == token && MINECRAFT.getConnection() == connection) {
                        callback.accept(completed, error);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            callback.accept(null, error);
        }
    }

    private static String truncateFileName(String fileName) {
        if (fileName.length() <= RomTransferLimits.MAX_FILE_NAME_CHARS) {
            return fileName;
        }
        return fileName.substring(0, RomTransferLimits.MAX_FILE_NAME_CHARS - 4)
                + ".nes";
    }

    private static void overlay(Component message) {
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                && cn.piq.fcarcade.client.ui.DeviceNoticePolicy.errorKey(translated.getKey()))
            cn.piq.fcarcade.client.ui.DeviceNotices.record("FC",message.getString());
        else MINECRAFT.gui.setOverlayMessage(message, false);
    }

    private static void fail(String context, Throwable error) {
        cn.piq.fcarcade.client.ui.DeviceNotices.record("FC",context,error);
    }

    private static final class Upload {
        private final String sha256;
        private final byte[] bytes;
        private int offset;

        private Upload(String sha256, byte[] bytes) {
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }
}
