package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeResumeDecisionPayload(
        BlockPos blockPos,
        String romSha256,
        boolean resume
) implements CustomPacketPayload {
    public ArcadeResumeDecisionPayload {
        blockPos = blockPos.immutable();
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
    }

    public static final Type<ArcadeResumeDecisionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_resume_decision"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeResumeDecisionPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeBoolean(payload.resume);
                    },
                    buffer -> new ArcadeResumeDecisionPayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
