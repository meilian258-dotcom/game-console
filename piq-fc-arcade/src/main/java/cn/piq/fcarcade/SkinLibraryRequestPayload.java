package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SkinLibraryRequestPayload(BlockPos blockPos)
        implements CustomPacketPayload {
    public SkinLibraryRequestPayload {
        blockPos = blockPos.immutable();
    }

    public static final Type<SkinLibraryRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_library_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinLibraryRequestPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBlockPos(payload.blockPos),
            buffer -> new SkinLibraryRequestPayload(buffer.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
