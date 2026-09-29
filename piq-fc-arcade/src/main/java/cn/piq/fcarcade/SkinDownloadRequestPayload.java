package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SkinDownloadRequestPayload(String sha256)
        implements CustomPacketPayload {
    public SkinDownloadRequestPayload {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
    }

    public static final Type<SkinDownloadRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_download_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinDownloadRequestPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.sha256, 64),
            buffer -> new SkinDownloadRequestPayload(buffer.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
