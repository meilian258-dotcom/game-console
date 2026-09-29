package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomRenamePayload(
        BlockPos blockPos,
        String romSha256,
        String displayName
) implements CustomPacketPayload {
    public RomRenamePayload {
        blockPos = blockPos.immutable();
        displayName = displayName == null ? "" : displayName.strip();
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (displayName.isBlank() || displayName.length() > 80
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ROM 显示名称无效");
        }
    }

    public static final Type<RomRenamePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_rename"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomRenamePayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeUtf(payload.displayName, 80);
                    },
                    buffer -> new RomRenamePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64),
                            buffer.readUtf(80)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
