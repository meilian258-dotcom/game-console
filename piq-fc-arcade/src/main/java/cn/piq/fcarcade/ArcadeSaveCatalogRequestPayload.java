package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSaveCatalogRequestPayload(BlockPos blockPos)
        implements CustomPacketPayload {
    public ArcadeSaveCatalogRequestPayload {
        blockPos = blockPos.immutable();
    }

    public static final Type<ArcadeSaveCatalogRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_save_catalog_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSaveCatalogRequestPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ArcadeSaveCatalogRequestPayload::blockPos,
                    ArcadeSaveCatalogRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
