package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeJoinPromptPayload(
        BlockPos blockPos,
        String romSha256
) implements CustomPacketPayload {
    public ArcadeJoinPromptPayload {
        romSha256 = romSha256 == null ? "" : romSha256;
        if (!romSha256.isEmpty() && !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
    }
    public static final Type<ArcadeJoinPromptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_join_prompt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeJoinPromptPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                    },
                    buffer -> new ArcadeJoinPromptPayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
