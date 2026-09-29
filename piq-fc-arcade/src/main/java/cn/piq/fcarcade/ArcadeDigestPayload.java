package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeDigestPayload(
        long sessionId,
        int epoch,
        long frame,
        long digest
) implements CustomPacketPayload {
    public static final Type<ArcadeDigestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_digest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeDigestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeLong(payload.sessionId);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeVarLong(payload.frame);
                        buffer.writeLong(payload.digest);
                    },
                    buffer -> new ArcadeDigestPayload(
                            buffer.readLong(),
                            buffer.readVarInt(),
                            buffer.readVarLong(),
                            buffer.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
