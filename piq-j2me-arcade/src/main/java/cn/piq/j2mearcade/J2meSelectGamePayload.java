package cn.piq.j2mearcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record J2meSelectGamePayload(BlockPos blockPos, String fileName)
        implements CustomPacketPayload {
    public J2meSelectGamePayload {
        blockPos = blockPos.immutable();
        fileName = cn.piq.j2mearcade.world.J2meArcadeBlockEntity.normalizeFileName(fileName);
    }

    public static final Type<J2meSelectGamePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PiqJ2meArcadeMod.MOD_ID, "select_game"));
    public static final StreamCodec<RegistryFriendlyByteBuf, J2meSelectGamePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos());
                        buffer.writeUtf(payload.fileName(), 128);
                    },
                    buffer -> new J2meSelectGamePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(128)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
