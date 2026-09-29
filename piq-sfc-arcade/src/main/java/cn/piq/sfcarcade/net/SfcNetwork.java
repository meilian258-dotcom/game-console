// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.net;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.server.SfcServerManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SfcNetwork {
    public static final int CHUNK_BYTES = 128 * 1024;
    public static final int CHUNKS_PER_TICK = 2;

    private SfcNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(LibraryRequestPayload.TYPE, LibraryRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        SfcServerManager.openLibrary((ServerPlayer) context.player(), payload.pos())));
        registrar.playToServer(SelectRomPayload.TYPE, SelectRomPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        SfcServerManager.selectRom((ServerPlayer) context.player(),
                                payload.pos(), payload.sha256())));
        registrar.playToServer(UploadStartPayload.TYPE, UploadStartPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        SfcServerManager.beginUpload((ServerPlayer) context.player(), payload)));
        registrar.playToServer(UploadChunkPayload.TYPE, UploadChunkPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        SfcServerManager.acceptUploadChunk(
                                (ServerPlayer) context.player(), payload)));
        registrar.playToServer(DownloadRequestPayload.TYPE, DownloadRequestPayload.STREAM_CODEC,
                (payload, context) -> {
                    var source=context.connection();var player=(ServerPlayer)context.player();
                    context.enqueueWork(() -> SfcServerManager.requestDownload(player,payload.sha256(),source));
                });
        registrar.playToServer(SessionRequestPayload.TYPE, SessionRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        SfcServerManager.requestSession(
                                (ServerPlayer) context.player(), payload.pos(), payload.stop())));

        registrar.playToClient(LibraryPayload.TYPE, LibraryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) {
                        SfcClientSupport.openLibrary(payload);
                    }
                }));
        registrar.playToClient(DownloadStartPayload.TYPE, DownloadStartPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) SfcClientSupport.startDownload(payload);
                }));
        registrar.playToClient(DownloadChunkPayload.TYPE, DownloadChunkPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) SfcClientSupport.acceptDownload(payload);
                }));
        registrar.playToClient(SessionStatePayload.TYPE, SessionStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) SfcClientSupport.acceptSession(payload);
                }));
    }

    public static void requestLibrary(BlockPos pos) {
        PacketDistributor.sendToServer(new LibraryRequestPayload(pos));
    }

    public static void selectRom(BlockPos pos, String sha256) {
        PacketDistributor.sendToServer(new SelectRomPayload(pos, sha256));
    }

    public static void requestDownload(String sha256) {
        PacketDistributor.sendToServer(new DownloadRequestPayload(sha256));
    }

    public static void requestSession(BlockPos pos, boolean stop) {
        PacketDistributor.sendToServer(new SessionRequestPayload(pos, stop));
    }

    public static void beginUpload(UploadStartPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void uploadChunk(UploadChunkPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendLibrary(ServerPlayer player, LibraryPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendDownloadStart(ServerPlayer player, DownloadStartPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendDownloadChunk(ServerPlayer player, DownloadChunkPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSession(ServerPlayer player, SessionStatePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SfcArcadeMod.MOD_ID, path);
    }

    public record CatalogEntry(String fileName, String sha256, int size) {
        public CatalogEntry {
            fileName = fileName == null ? "" : fileName;
            sha256 = sha256 == null ? "" : sha256;
        }

        private static void write(RegistryFriendlyByteBuf buffer, CatalogEntry value) {
            buffer.writeUtf(value.fileName, 128);
            buffer.writeUtf(value.sha256, 64);
            buffer.writeVarInt(value.size);
        }

        private static CatalogEntry read(RegistryFriendlyByteBuf buffer) {
            return new CatalogEntry(buffer.readUtf(128), buffer.readUtf(64), buffer.readVarInt());
        }
    }

    public record LibraryRequestPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<LibraryRequestPayload> TYPE = new Type<>(id("library_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LibraryRequestPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> buffer.writeBlockPos(value.pos),
                        buffer -> new LibraryRequestPayload(buffer.readBlockPos()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record LibraryPayload(BlockPos pos, String selectedHash, List<CatalogEntry> entries)
            implements CustomPacketPayload {
        public static final Type<LibraryPayload> TYPE = new Type<>(id("library"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LibraryPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeBlockPos(value.pos);
                    buffer.writeUtf(value.selectedHash, 64);
                    buffer.writeVarInt(value.entries.size());
                    value.entries.forEach(entry -> CatalogEntry.write(buffer, entry));
                }, buffer -> {
                    BlockPos pos = buffer.readBlockPos();
                    String selected = buffer.readUtf(64);
                    int count = Math.min(buffer.readVarInt(), 256);
                    List<CatalogEntry> entries = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) entries.add(CatalogEntry.read(buffer));
                    return new LibraryPayload(pos, selected, List.copyOf(entries));
                });

        public LibraryPayload {
            selectedHash = selectedHash == null ? "" : selectedHash;
            entries = List.copyOf(entries);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SelectRomPayload(BlockPos pos, String sha256) implements CustomPacketPayload {
        public static final Type<SelectRomPayload> TYPE = new Type<>(id("select_rom"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectRomPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeBlockPos(value.pos);
                    buffer.writeUtf(value.sha256, 64);
                }, buffer -> new SelectRomPayload(buffer.readBlockPos(), buffer.readUtf(64)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DownloadRequestPayload(String sha256) implements CustomPacketPayload {
        public static final Type<DownloadRequestPayload> TYPE = new Type<>(id("download_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadRequestPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> buffer.writeUtf(value.sha256, 64),
                        buffer -> new DownloadRequestPayload(buffer.readUtf(64)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SessionRequestPayload(BlockPos pos, boolean stop)
            implements CustomPacketPayload {
        public static final Type<SessionRequestPayload> TYPE =
                new Type<>(id("session_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SessionRequestPayload>
                STREAM_CODEC = StreamCodec.of((buffer, value) -> {
                    buffer.writeBlockPos(value.pos);
                    buffer.writeBoolean(value.stop);
                }, buffer -> new SessionRequestPayload(
                        buffer.readBlockPos(), buffer.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SessionStatePayload(
            BlockPos pos,
            UUID sessionId,
            boolean active,
            boolean owner,
            String playerName,
            String romHash,
            String romName
    ) implements CustomPacketPayload {
        public static final UUID INACTIVE_SESSION = new UUID(0L, 0L);
        public static final Type<SessionStatePayload> TYPE =
                new Type<>(id("session_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SessionStatePayload>
                STREAM_CODEC = StreamCodec.of((buffer, value) -> {
                    buffer.writeBlockPos(value.pos);
                    buffer.writeUUID(value.sessionId);
                    buffer.writeBoolean(value.active);
                    buffer.writeBoolean(value.owner);
                    buffer.writeUtf(value.playerName, 64);
                    buffer.writeUtf(value.romHash, 64);
                    buffer.writeUtf(value.romName, 128);
                }, buffer -> new SessionStatePayload(
                        buffer.readBlockPos(),
                        buffer.readUUID(),
                        buffer.readBoolean(),
                        buffer.readBoolean(),
                        buffer.readUtf(64),
                        buffer.readUtf(64),
                        buffer.readUtf(128)));

        public SessionStatePayload {
            pos = pos.immutable();
            sessionId = sessionId == null ? INACTIVE_SESSION : sessionId;
            playerName = playerName == null ? "" : playerName;
            romHash = romHash == null ? "" : romHash;
            romName = romName == null ? "" : romName;
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UploadStartPayload(
            BlockPos pos,
            String fileName,
            String sha256,
            int totalBytes
    ) implements CustomPacketPayload {
        public static final Type<UploadStartPayload> TYPE = new Type<>(id("upload_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStartPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeBlockPos(value.pos);
                    buffer.writeUtf(value.fileName, 128);
                    buffer.writeUtf(value.sha256, 64);
                    buffer.writeVarInt(value.totalBytes);
                }, buffer -> new UploadStartPayload(
                        buffer.readBlockPos(),
                        buffer.readUtf(128),
                        buffer.readUtf(64),
                        buffer.readVarInt()));

        public UploadStartPayload {
            fileName = fileName == null ? "" : fileName;
            sha256 = sha256 == null ? "" : sha256;
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UploadChunkPayload(String sha256, int offset, byte[] data)
            implements CustomPacketPayload {
        public static final Type<UploadChunkPayload> TYPE = new Type<>(id("upload_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunkPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeUtf(value.sha256, 64);
                    buffer.writeVarInt(value.offset);
                    buffer.writeByteArray(value.data);
                }, buffer -> new UploadChunkPayload(
                        buffer.readUtf(64),
                        buffer.readVarInt(),
                        buffer.readByteArray(CHUNK_BYTES)));

        public UploadChunkPayload {
            sha256 = sha256 == null ? "" : sha256;
            data = data == null ? new byte[0] : data.clone();
        }

        @Override public byte[] data() { return data.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DownloadStartPayload(String fileName, String sha256, int totalBytes)
            implements CustomPacketPayload {
        public static final Type<DownloadStartPayload> TYPE = new Type<>(id("download_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadStartPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeUtf(value.fileName, 128);
                    buffer.writeUtf(value.sha256, 64);
                    buffer.writeVarInt(value.totalBytes);
                }, buffer -> new DownloadStartPayload(
                        buffer.readUtf(128), buffer.readUtf(64), buffer.readVarInt()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DownloadChunkPayload(String sha256, int offset, byte[] data)
            implements CustomPacketPayload {
        public static final Type<DownloadChunkPayload> TYPE = new Type<>(id("download_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadChunkPayload> STREAM_CODEC =
                StreamCodec.of((buffer, value) -> {
                    buffer.writeUtf(value.sha256, 64);
                    buffer.writeVarInt(value.offset);
                    buffer.writeByteArray(value.data);
                }, buffer -> new DownloadChunkPayload(
                        buffer.readUtf(64), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES)));

        public DownloadChunkPayload {
            data = data.clone();
        }

        @Override public byte[] data() { return data.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
