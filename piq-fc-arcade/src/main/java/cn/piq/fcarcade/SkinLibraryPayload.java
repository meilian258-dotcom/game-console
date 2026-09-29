package cn.piq.fcarcade;

import cn.piq.fcarcade.skin.SkinDescriptor;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import cn.piq.fcarcade.skin.SkinLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record SkinLibraryPayload(
        BlockPos blockPos,
        String selectedSha256,
        List<SkinDescriptor> serverSkins
) implements CustomPacketPayload {
    public SkinLibraryPayload {
        blockPos = blockPos.immutable();
        selectedSha256 = selectedSha256 == null ? "" : selectedSha256;
        if (!selectedSha256.isEmpty()
                && !selectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("已选皮肤 SHA-256 无效");
        }
        serverSkins = List.copyOf(serverSkins);
        if (serverSkins.size() > SkinTransferLimits.MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("服务器皮肤目录过大");
        }
    }

    public static final Type<SkinLibraryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "skin_library"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinLibraryPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.blockPos);
                buffer.writeUtf(payload.selectedSha256, 64);
                buffer.writeVarInt(payload.serverSkins.size());
                for (SkinDescriptor descriptor : payload.serverSkins) {
                    buffer.writeUtf(
                            descriptor.name(),
                            SkinTransferLimits.MAX_NAME_CHARS);
                    buffer.writeUtf(descriptor.sha256(), 64);
                    buffer.writeVarInt(descriptor.size());
                    buffer.writeVarInt(descriptor.layout().id());
                }
            },
            buffer -> {
                BlockPos pos = buffer.readBlockPos();
                String selected = buffer.readUtf(64);
                int count = buffer.readVarInt();
                if (count < 0
                        || count > SkinTransferLimits.MAX_CATALOG_ENTRIES) {
                    throw new IllegalArgumentException("服务器皮肤目录数量无效");
                }
                List<SkinDescriptor> descriptors = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    descriptors.add(new SkinDescriptor(
                            buffer.readUtf(SkinTransferLimits.MAX_NAME_CHARS),
                            buffer.readUtf(64),
                            buffer.readVarInt(),
                            SkinLayout.fromId(buffer.readVarInt())));
                }
                return new SkinLibraryPayload(pos, selected, descriptors);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
