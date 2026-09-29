package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ArcadeJoinDecisionPayload(
        long sessionId,
        UUID applicantId,
        boolean accepted, UUID requestToken
) implements CustomPacketPayload {
    public ArcadeJoinDecisionPayload(long sessionId,UUID applicantId,boolean accepted){this(sessionId,applicantId,accepted,null);}
    public ArcadeJoinDecisionPayload {
        if (sessionId < 0 || applicantId == null) {
            throw new IllegalArgumentException("加入决定无效");
        }
    }

    public static final Type<ArcadeJoinDecisionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "join_decision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeJoinDecisionPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarLong(payload.sessionId);
                        buffer.writeUUID(payload.applicantId);
                        buffer.writeBoolean(payload.accepted);
                        buffer.writeBoolean(payload.requestToken!=null);if(payload.requestToken!=null)buffer.writeUUID(payload.requestToken);
                    },
                    buffer -> new ArcadeJoinDecisionPayload(
                            buffer.readVarLong(),
                            buffer.readUUID(),
                            buffer.readBoolean(),buffer.readBoolean()?buffer.readUUID():null));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
