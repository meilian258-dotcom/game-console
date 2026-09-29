package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record ArcadeSaveCatalogPayload(
        BlockPos blockPos,
        List<ArcadeSaveCatalogEntry> entries
) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 1024;

    public ArcadeSaveCatalogPayload {
        blockPos = blockPos.immutable();
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("玩家存档目录数量过大");
        }
    }

    public static final Type<ArcadeSaveCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_save_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSaveCatalogPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeVarInt(payload.entries.size());
                        for (ArcadeSaveCatalogEntry entry : payload.entries) {
                            buffer.writeUtf(entry.storageId(), 64);
                            buffer.writeUtf(entry.owner(), 128);
                            buffer.writeUtf(entry.romName(), 160);
                            buffer.writeUtf(entry.romSha256(), 64);
                            buffer.writeUtf(entry.slotName(), 32);
                            buffer.writeVarInt(entry.players());
                            buffer.writeLong(entry.modifiedEpochMillis());
                            buffer.writeLong(entry.fileBytes());
                            buffer.writeBoolean(entry.legacy());
                        }
                    },
                    buffer -> {
                        BlockPos blockPos = buffer.readBlockPos();
                        int count = buffer.readVarInt();
                        if (count < 0 || count > MAX_ENTRIES) {
                            throw new IllegalArgumentException(
                                    "玩家存档目录数量无效");
                        }
                        List<ArcadeSaveCatalogEntry> entries =
                                new ArrayList<>(count);
                        for (int index = 0; index < count; index++) {
                            entries.add(new ArcadeSaveCatalogEntry(
                                    buffer.readUtf(64),
                                    buffer.readUtf(128),
                                    buffer.readUtf(160),
                                    buffer.readUtf(64),
                                    buffer.readUtf(32),
                                    buffer.readVarInt(),
                                    buffer.readLong(),
                                    buffer.readLong(),
                                    buffer.readBoolean()));
                        }
                        return new ArcadeSaveCatalogPayload(blockPos, entries);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
