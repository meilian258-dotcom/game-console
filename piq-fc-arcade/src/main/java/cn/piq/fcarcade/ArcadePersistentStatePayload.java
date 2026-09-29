package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadePersistentStatePayload(
        long sessionId,
        int epoch,
        byte[] state
) implements CustomPacketPayload {
    public ArcadePersistentStatePayload {
        state = state.clone();
        if (sessionId < 0 || epoch <= 0
                || state.length == 0
                || state.length > ArcadeSnapshotUploadPayload.MAX_STATE_BYTES) {
            throw new IllegalArgumentException("NES 持久化状态无效");
        }
    }

    public static final Type<ArcadePersistentStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_persistent_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadePersistentStatePayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarLong(payload.sessionId);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeByteArray(payload.state);
                    },
                    buffer -> new ArcadePersistentStatePayload(
                            buffer.readVarLong(),
                            buffer.readVarInt(),
                            buffer.readByteArray(
                                    ArcadeSnapshotUploadPayload.MAX_STATE_BYTES)));

    @Override
    public byte[] state() {
        return state.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
