package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomTransferLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomDownloadChunkPayload(
        String sha256,
        int offset,
        byte[] data
) implements CustomPacketPayload {
    public RomDownloadChunkPayload {
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (offset < 0 || data == null || data.length == 0
                || data.length > RomTransferLimits.CHUNK_BYTES) {
            throw new IllegalArgumentException("ROM 下载分块无效");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public static final Type<RomDownloadChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_download_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomDownloadChunkPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.offset);
                buffer.writeByteArray(payload.data);
            },
            buffer -> new RomDownloadChunkPayload(
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readByteArray(RomTransferLimits.CHUNK_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
