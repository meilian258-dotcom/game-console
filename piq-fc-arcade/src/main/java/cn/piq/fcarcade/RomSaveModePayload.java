package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomSaveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomSaveModePayload(
        BlockPos blockPos,
        String romSha256,
        RomSaveMode saveMode
) implements CustomPacketPayload {
    public RomSaveModePayload {
        blockPos = blockPos.immutable();
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (saveMode == null) {
            throw new IllegalArgumentException("ROM 存档模式无效");
        }
    }

    public static final Type<RomSaveModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_save_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomSaveModePayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeVarInt(payload.saveMode.id());
                    },
                    buffer -> new RomSaveModePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64),
                            RomSaveMode.fromId(buffer.readVarInt())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
