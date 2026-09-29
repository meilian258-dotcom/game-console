// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.net.SfcNetwork;
import cn.piq.sfcarcade.rom.SfcRomEntry;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ClientSfcRomTransfers {
    private static final Map<String, Consumer<Path>> COMPLETION = new HashMap<>();
    private static IncomingDownload incoming;
    private static OutgoingUpload upload;
    private static int lastUploadPlayerTick = Integer.MIN_VALUE;

    private ClientSfcRomTransfers() {
    }

    public static void ensureLocal(String sha256, Consumer<Path> completion) {
        try {
            SfcRomEntry local = ClientSfcRomLibrary.find(sha256);
            if (local != null) {
                completion.accept(local.path());
                return;
            }
            COMPLETION.put(sha256, completion);
            overlay(Component.translatable("message.piq_sfc_arcade.downloading"));
            SfcNetwork.requestDownload(sha256);
        } catch (IOException | IllegalArgumentException exception) {
            overlay(Component.translatable(
                    "message.piq_sfc_arcade.download_failed", exception.getMessage()));
        }
    }

    public static void select(BlockPos pos, String sha256, boolean serverHasRom) {
        try {
            SfcRomEntry local = ClientSfcRomLibrary.find(sha256);
            if (local == null) {
                overlay(Component.translatable(
                        "message.piq_sfc_arcade.upload_local_missing"));
                return;
            }
            if (serverHasRom) {
                SfcNetwork.selectRom(pos, sha256);
                closeScreen();
                return;
            }
            if (upload != null) {
                overlay(Component.translatable(
                        "message.piq_sfc_arcade.transfer_busy"));
                return;
            }
            byte[] bytes = Files.readAllBytes(local.path());
            if (bytes.length <= 0 || bytes.length > SfcRomRepository.MAX_SOURCE_BYTES) {
                throw new IOException("SFC ROM size is outside the safety limit");
            }
            upload = new OutgoingUpload(local.sha256(), bytes);
            SfcNetwork.beginUpload(new SfcNetwork.UploadStartPayload(
                    pos, local.fileName(), local.sha256(), bytes.length));
            closeScreen();
            overlay(Component.translatable(
                    "message.piq_sfc_arcade.uploading", local.fileName()));
        } catch (IOException | IllegalArgumentException exception) {
            overlay(Component.translatable(
                    "message.piq_sfc_arcade.upload_failed", readable(exception)));
        }
    }

    public static void update() {
        Minecraft minecraft = Minecraft.getInstance();
        if (upload == null || minecraft.player == null) return;
        int playerTick = minecraft.player.tickCount;
        if (playerTick == lastUploadPlayerTick) return;
        lastUploadPlayerTick = playerTick;
        for (int chunk = 0;
             chunk < SfcNetwork.CHUNKS_PER_TICK && upload.offset < upload.bytes.length;
             chunk++) {
            int end = Math.min(upload.offset + SfcNetwork.CHUNK_BYTES, upload.bytes.length);
            SfcNetwork.uploadChunk(new SfcNetwork.UploadChunkPayload(
                    upload.sha256,
                    upload.offset,
                    Arrays.copyOfRange(upload.bytes, upload.offset, end)));
            upload.offset = end;
        }
        overlay(Component.translatable(
                "message.piq_sfc_arcade.upload_progress",
                upload.offset * 100 / upload.bytes.length));
        if (upload.offset >= upload.bytes.length) upload = null;
    }

    public static void start(SfcNetwork.DownloadStartPayload payload) {
        if (payload.totalBytes() <= 0
                || payload.totalBytes() > SfcRomRepository.MAX_SOURCE_BYTES
                || !payload.sha256().matches("[0-9a-f]{64}")) {
            fail(payload.sha256(), "invalid transfer metadata");
            return;
        }
        incoming = new IncomingDownload(
                payload.fileName(), payload.sha256(), new byte[payload.totalBytes()]);
    }

    public static void accept(SfcNetwork.DownloadChunkPayload payload) {
        if (incoming == null || !incoming.sha256.equals(payload.sha256())) return;
        byte[] data = payload.data();
        if (payload.offset() != incoming.offset || data.length == 0
                || payload.offset() + data.length > incoming.bytes.length) {
            fail(payload.sha256(), "invalid transfer chunk");
            return;
        }
        System.arraycopy(data, 0, incoming.bytes, incoming.offset, data.length);
        incoming.offset += data.length;
        if (incoming.offset < incoming.bytes.length) return;
        try {
            SfcRomEntry stored = ClientSfcRomLibrary.repository().storeVerified(
                    incoming.fileName, incoming.sha256, incoming.bytes);
            Consumer<Path> completion = COMPLETION.remove(incoming.sha256);
            overlay(Component.translatable(
                    "message.piq_sfc_arcade.downloaded", stored.fileName()));
            incoming = null;
            if (completion != null) completion.accept(stored.path());
        } catch (IOException | IllegalArgumentException exception) {
            fail(payload.sha256(), exception.getMessage());
        }
    }

    private static void fail(String sha256, String reason) {
        COMPLETION.remove(sha256);
        incoming = null;
        overlay(Component.translatable("message.piq_sfc_arcade.download_failed", reason));
    }

    private static void overlay(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) minecraft.gui.setOverlayMessage(component, false);
    }

    private static void closeScreen() {
        Minecraft.getInstance().setScreen(null);
    }

    private static String readable(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static final class IncomingDownload {
        private final String fileName;
        private final String sha256;
        private final byte[] bytes;
        private int offset;

        private IncomingDownload(String fileName, String sha256, byte[] bytes) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    private static final class OutgoingUpload {
        private final String sha256;
        private final byte[] bytes;
        private int offset;

        private OutgoingUpload(String sha256, byte[] bytes) {
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }
}
