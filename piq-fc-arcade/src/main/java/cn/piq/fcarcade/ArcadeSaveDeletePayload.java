package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSaveDeletePayload(BlockPos blockPos, String storageId)
        implements CustomPacketPayload {
    public ArcadeSaveDeletePayload {
        blockPos = blockPos.immutable();
        if (storageId == null || !storageId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("存档文件标识无效");
        }
    }

    public static final Type<ArcadeSaveDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_save_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSaveDeletePayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ArcadeSaveDeletePayload::blockPos,
                    net.minecraft.network.codec.ByteBufCodecs.stringUtf8(64),
                    ArcadeSaveDeletePayload::storageId,
                    ArcadeSaveDeletePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
