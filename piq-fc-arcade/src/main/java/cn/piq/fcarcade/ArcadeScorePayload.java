package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeScorePayload(
        long sessionId,
        int score
) implements CustomPacketPayload {
    public static final Type<ArcadeScorePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_score"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeScorePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeLong(payload.sessionId);
                        buffer.writeVarInt(payload.score);
                    },
                    buffer -> new ArcadeScorePayload(
                            buffer.readLong(),
                            buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
