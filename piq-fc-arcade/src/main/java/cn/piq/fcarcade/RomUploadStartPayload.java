package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomTransferLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomUploadStartPayload(
        BlockPos blockPos,
        String fileName,
        String sha256,
        int totalBytes
) implements CustomPacketPayload {
    public RomUploadStartPayload {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > RomTransferLimits.MAX_FILE_NAME_CHARS) {
            throw new IllegalArgumentException("ROM 文件名无效");
        }
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (totalBytes <= 0 || totalBytes > RomRepository.MAX_ROM_BYTES) {
            throw new IllegalArgumentException("ROM 上传大小无效");
        }
    }

    public static final Type<RomUploadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_upload_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomUploadStartPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.blockPos);
                buffer.writeUtf(
                        payload.fileName,
                        RomTransferLimits.MAX_FILE_NAME_CHARS);
                buffer.writeUtf(payload.sha256, 64);
                buffer.writeVarInt(payload.totalBytes);
            },
            buffer -> new RomUploadStartPayload(
                    buffer.readBlockPos(),
                    buffer.readUtf(RomTransferLimits.MAX_FILE_NAME_CHARS),
                    buffer.readUtf(64),
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
