package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomRepository;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomDownloadRequestPayload(String sha256) implements CustomPacketPayload {
    public RomDownloadRequestPayload {
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
    }

    public static final Type<RomDownloadRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_download_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomDownloadRequestPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.sha256, 64),
            buffer -> new RomDownloadRequestPayload(buffer.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
