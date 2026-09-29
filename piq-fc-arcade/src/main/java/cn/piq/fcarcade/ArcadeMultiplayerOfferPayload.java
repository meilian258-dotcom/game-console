package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeMultiplayerOfferPayload(long sessionId)
        implements CustomPacketPayload {
    public ArcadeMultiplayerOfferPayload {
        if (sessionId < 0) throw new IllegalArgumentException("会话编号无效");
    }

    public static final Type<ArcadeMultiplayerOfferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "multiplayer_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeMultiplayerOfferPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> buffer.writeVarLong(payload.sessionId),
                    buffer -> new ArcadeMultiplayerOfferPayload(buffer.readVarLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
