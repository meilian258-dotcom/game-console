package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeFramePayload(
        long sessionId,
        int epoch,
        long targetFrame,
        int playerOneMask,
        int playerTwoMask,
        int zapperState
) implements CustomPacketPayload {
    public ArcadeFramePayload(long session,int epoch,long frame,int one,int two){this(session,epoch,frame,one,two,cn.piq.fcarcade.session.ZapperInput.NEUTRAL);}
    public ArcadeFramePayload {cn.piq.fcarcade.session.ZapperInput.validate(zapperState);}
    public static final Type<ArcadeFramePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_frame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeFramePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeLong(payload.sessionId);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeVarLong(payload.targetFrame);
                        buffer.writeByte(payload.playerOneMask);
                        buffer.writeByte(payload.playerTwoMask);
                        buffer.writeVarInt(payload.zapperState);
                    },
                    buffer -> new ArcadeFramePayload(
                            buffer.readLong(),
                            buffer.readVarInt(),
                            buffer.readVarLong(),
                            buffer.readUnsignedByte(),
                            buffer.readUnsignedByte(),buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
