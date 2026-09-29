package cn.piq.fcarcade;

import cn.piq.fcarcade.skin.SkinDescriptor;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SkinDownloadStartPayload(
        String name,
        String sha256,
        int totalBytes
) implements CustomPacketPayload {
    public SkinDownloadStartPayload {
        name = SkinDescriptor.normalizeName(name);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (totalBytes <= 0
                || totalBytes > SkinTransferLimits.MAX_PNG_BYTES) {
            throw new IllegalArgumentException("皮肤下载大小无效");
        }
    }

    public static final Type<SkinDownloadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_download_start"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinDownloadStartPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.name, SkinTransferLimits.MAX_NAME_CHARS);
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.totalBytes);
            },
            buffer -> new SkinDownloadStartPayload(
                    buffer.readUtf(SkinTransferLimits.MAX_NAME_CHARS),
                    buffer.readUtf(64),
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
