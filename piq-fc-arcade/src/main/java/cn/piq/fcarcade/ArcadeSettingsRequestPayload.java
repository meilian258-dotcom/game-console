package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSettingsRequestPayload(BlockPos blockPos)
        implements CustomPacketPayload {
    public ArcadeSettingsRequestPayload {
        blockPos = blockPos.immutable();
    }

    public static final Type<ArcadeSettingsRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_settings_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSettingsRequestPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ArcadeSettingsRequestPayload::blockPos,
                    ArcadeSettingsRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
