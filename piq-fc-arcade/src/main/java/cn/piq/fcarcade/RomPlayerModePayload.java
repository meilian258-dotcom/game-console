package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomPlayerModePayload(
        BlockPos blockPos,
        String romSha256,
        int maxPlayers
) implements CustomPacketPayload {
    public RomPlayerModePayload {
        blockPos = blockPos.immutable();
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (maxPlayers < 1 || maxPlayers > 2) {
            throw new IllegalArgumentException("ROM 玩家数量无效");
        }
    }

    public static final Type<RomPlayerModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_player_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomPlayerModePayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeVarInt(payload.maxPlayers);
                    },
                    buffer -> new RomPlayerModePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64),
                            buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
