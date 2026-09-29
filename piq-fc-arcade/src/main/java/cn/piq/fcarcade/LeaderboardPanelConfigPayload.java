package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client snapshot used by the OP leaderboard panel editor. */
public record LeaderboardPanelConfigPayload(
        BlockPos blockPos,
        boolean enabled,
        int intervalSeconds,
        int scalePercent,
        int offsetXHundredths,
        int offsetYHundredths,
        int depthHundredths
) implements CustomPacketPayload {
    public LeaderboardPanelConfigPayload {
        blockPos = blockPos.immutable();
    }

    public static final Type<LeaderboardPanelConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "leaderboard_panel_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LeaderboardPanelConfigPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeBoolean(payload.enabled);
                        buffer.writeVarInt(payload.intervalSeconds);
                        buffer.writeVarInt(payload.scalePercent);
                        buffer.writeInt(payload.offsetXHundredths);
                        buffer.writeInt(payload.offsetYHundredths);
                        buffer.writeInt(payload.depthHundredths);
                    },
                    buffer -> new LeaderboardPanelConfigPayload(
                            buffer.readBlockPos(),
                            buffer.readBoolean(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
