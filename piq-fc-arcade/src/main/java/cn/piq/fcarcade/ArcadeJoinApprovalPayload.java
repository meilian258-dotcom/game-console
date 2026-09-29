package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ArcadeJoinApprovalPayload(
        long sessionId,
        UUID applicantId,
        String applicantName, UUID requestToken
) implements CustomPacketPayload {
    public ArcadeJoinApprovalPayload(long sessionId,UUID applicantId,String applicantName){this(sessionId,applicantId,applicantName,null);}
    public ArcadeJoinApprovalPayload {
        if (sessionId < 0 || applicantId == null) {
            throw new IllegalArgumentException("加入申请无效");
        }
        if (applicantName == null || applicantName.isBlank()
                || applicantName.length() > 64) {
            throw new IllegalArgumentException("申请玩家名称无效");
        }
    }

    public static final Type<ArcadeJoinApprovalPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "join_approval"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeJoinApprovalPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarLong(payload.sessionId);
                        buffer.writeUUID(payload.applicantId);
                        buffer.writeUtf(payload.applicantName, 64);
                        buffer.writeBoolean(payload.requestToken!=null);if(payload.requestToken!=null)buffer.writeUUID(payload.requestToken);
                    },
                    buffer -> new ArcadeJoinApprovalPayload(
                            buffer.readVarLong(),
                            buffer.readUUID(),
                            buffer.readUtf(64),buffer.readBoolean()?buffer.readUUID():null));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
