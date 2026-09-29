package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSnapshotPayload(
        long sessionId,
        int epoch,
        long frame,
        byte[] state
) implements CustomPacketPayload {
    public ArcadeSnapshotPayload {
        state = state.clone();
        if (state.length == 0 || state.length > ArcadeSnapshotUploadPayload.MAX_STATE_BYTES) {
            throw new IllegalArgumentException("NES 临时状态大小非法");
        }
    }

    public static final Type<ArcadeSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSnapshotPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeLong(payload.sessionId);
                buffer.writeVarInt(payload.epoch);
                buffer.writeVarLong(payload.frame);
                buffer.writeByteArray(payload.state);
            },
            buffer -> new ArcadeSnapshotPayload(
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarLong(),
                    buffer.readByteArray(ArcadeSnapshotUploadPayload.MAX_STATE_BYTES)));

    @Override
    public byte[] state() {
        return state.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
