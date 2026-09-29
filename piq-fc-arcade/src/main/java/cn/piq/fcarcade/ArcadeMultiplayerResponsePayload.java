package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeMultiplayerResponsePayload(long sessionId, boolean enabled)
        implements CustomPacketPayload {
    public ArcadeMultiplayerResponsePayload {
        if (sessionId < 0) throw new IllegalArgumentException("会话编号无效");
    }

    public static final Type<ArcadeMultiplayerResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "multiplayer_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeMultiplayerResponsePayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarLong(payload.sessionId);
                        buffer.writeBoolean(payload.enabled);
                    },
                    buffer -> new ArcadeMultiplayerResponsePayload(
                            buffer.readVarLong(),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
