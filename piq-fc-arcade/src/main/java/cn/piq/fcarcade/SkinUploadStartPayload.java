package cn.piq.fcarcade;

import cn.piq.fcarcade.skin.SkinDescriptor;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SkinUploadStartPayload(
        BlockPos blockPos,
        String name,
        String sha256,
        int totalBytes
) implements CustomPacketPayload {
    public SkinUploadStartPayload {
        blockPos = blockPos.immutable();
        name = SkinDescriptor.normalizeName(name);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (totalBytes <= 0
                || totalBytes > SkinTransferLimits.MAX_PNG_BYTES) {
            throw new IllegalArgumentException("皮肤上传大小无效");
        }
    }

    public static final Type<SkinUploadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_upload_start"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinUploadStartPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.blockPos);
                buffer.writeUtf(payload.name, SkinTransferLimits.MAX_NAME_CHARS);
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.totalBytes);
            },
            buffer -> new SkinUploadStartPayload(
                    buffer.readBlockPos(),
                    buffer.readUtf(SkinTransferLimits.MAX_NAME_CHARS),
                    buffer.readUtf(64),
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
