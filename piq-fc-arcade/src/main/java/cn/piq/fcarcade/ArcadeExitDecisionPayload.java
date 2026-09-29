package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeExitDecisionPayload(long sessionId, boolean save)
        implements CustomPacketPayload {
    public static final Type<ArcadeExitDecisionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_exit_decision"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeExitDecisionPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarLong(payload.sessionId);
                        buffer.writeBoolean(payload.save);
                    },
                    buffer -> new ArcadeExitDecisionPayload(
                            buffer.readVarLong(),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
