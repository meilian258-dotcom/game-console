package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSnapshotRequestPayload(
        long sessionId,
        int epoch
) implements CustomPacketPayload {
    public static final Type<ArcadeSnapshotRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_snapshot_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSnapshotRequestPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeLong(payload.sessionId);
                buffer.writeVarInt(payload.epoch);
            },
            buffer -> new ArcadeSnapshotRequestPayload(
                    buffer.readLong(),
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
