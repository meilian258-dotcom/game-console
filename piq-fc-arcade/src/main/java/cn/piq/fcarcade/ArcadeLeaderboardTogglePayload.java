package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Enables or disables the idle leaderboard on one cabinet. */
public record ArcadeLeaderboardTogglePayload(
        BlockPos blockPos,
        boolean enabled
) implements CustomPacketPayload {
    public ArcadeLeaderboardTogglePayload {
        blockPos = blockPos.immutable();
    }

    public static final Type<ArcadeLeaderboardTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_leaderboard_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            ArcadeLeaderboardTogglePayload> STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeBoolean(payload.enabled);
                    },
                    buffer -> new ArcadeLeaderboardTogglePayload(
                            buffer.readBlockPos(),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
