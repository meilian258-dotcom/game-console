package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomTransferLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomDownloadStartPayload(
        String fileName,
        String sha256,
        int totalBytes
) implements CustomPacketPayload {
    public RomDownloadStartPayload {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > RomTransferLimits.MAX_FILE_NAME_CHARS) {
            throw new IllegalArgumentException("ROM 文件名无效");
        }
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (totalBytes <= 0 || totalBytes > RomRepository.MAX_ROM_BYTES) {
            throw new IllegalArgumentException("ROM 下载大小无效");
        }
    }

    public static final Type<RomDownloadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_download_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomDownloadStartPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(
                        payload.fileName,
                        RomTransferLimits.MAX_FILE_NAME_CHARS);
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.totalBytes);
            },
            buffer -> new RomDownloadStartPayload(
                    buffer.readUtf(RomTransferLimits.MAX_FILE_NAME_CHARS),
                    buffer.readUtf(64),
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
