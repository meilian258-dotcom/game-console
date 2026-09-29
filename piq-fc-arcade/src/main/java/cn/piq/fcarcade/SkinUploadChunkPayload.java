package cn.piq.fcarcade;

import cn.piq.fcarcade.skin.SkinTransferLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SkinUploadChunkPayload(
        String sha256,
        int offset,
        byte[] data
) implements CustomPacketPayload {
    public SkinUploadChunkPayload {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (offset < 0 || data == null || data.length == 0
                || data.length > SkinTransferLimits.CHUNK_BYTES) {
            throw new IllegalArgumentException("皮肤上传分块无效");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public static final Type<SkinUploadChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_upload_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinUploadChunkPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.offset);
                buffer.writeByteArray(payload.data);
            },
            buffer -> new SkinUploadChunkPayload(
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readByteArray(SkinTransferLimits.CHUNK_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
