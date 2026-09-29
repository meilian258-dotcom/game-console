package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeInputPayload(
        long sessionId,
        int epoch,
        int sequence,
        int buttonMask,
        boolean forceRelease
) implements CustomPacketPayload {
    public ArcadeInputPayload(long sessionId, int epoch, int sequence, int buttonMask) {
        this(sessionId, epoch, sequence, buttonMask, false);
    }
    public static final Type<ArcadeInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeInputPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeLong(payload.sessionId);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeVarInt(payload.sequence);
                        buffer.writeByte(payload.buttonMask);
                        buffer.writeBoolean(payload.forceRelease);
                    },
                    buffer -> new ArcadeInputPayload(
                            buffer.readLong(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readUnsignedByte(),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
