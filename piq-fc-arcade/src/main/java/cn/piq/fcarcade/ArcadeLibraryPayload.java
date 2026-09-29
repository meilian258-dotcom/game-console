package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomTransferLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record ArcadeLibraryPayload(
        BlockPos blockPos,
        String selectedSha256,
        boolean leaderboardEnabled,
        List<RomCatalogEntry> serverRoms,
        int capabilities
) implements CustomPacketPayload {
    public ArcadeLibraryPayload {
        if(capabilities<0||(capabilities&~cn.piq.fcarcade.access.PlayerContentPolicy.ALL)!=0)throw new IllegalArgumentException("Invalid content capabilities");
        blockPos = blockPos.immutable();
        selectedSha256 = selectedSha256 == null ? "" : selectedSha256;
        if (!selectedSha256.isEmpty() && !selectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("已选 ROM SHA-256 无效");
        }
        serverRoms = List.copyOf(serverRoms);
        if (serverRoms.size() > RomTransferLimits.MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("服务器 ROM 目录过大");
        }
    }

    public ArcadeLibraryPayload(BlockPos blockPos,String selectedSha256,boolean leaderboardEnabled,List<RomCatalogEntry> serverRoms){this(blockPos,selectedSha256,leaderboardEnabled,serverRoms,0);}

    public static final Type<ArcadeLibraryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_library"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeLibraryPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.selectedSha256, 64);
                        buffer.writeBoolean(payload.leaderboardEnabled);
                        buffer.writeVarInt(payload.capabilities);
                        buffer.writeVarInt(payload.serverRoms.size());
                        for (RomCatalogEntry entry : payload.serverRoms) {
                            buffer.writeUtf(
                                    entry.fileName(),
                                    RomTransferLimits.MAX_FILE_NAME_CHARS);
                            buffer.writeUtf(entry.sha256(), 64);
                            buffer.writeVarInt(entry.size());
                            buffer.writeVarInt(entry.mapper());
                            buffer.writeVarInt(entry.maxPlayers());
                            buffer.writeVarInt(entry.saveMode().id());
                        }
                    },
                    buffer -> {
                        BlockPos pos = buffer.readBlockPos();
                        String selected = buffer.readUtf(64);
                        boolean leaderboardEnabled = buffer.readBoolean();
                        int capabilities = buffer.readVarInt();
                        int count = buffer.readVarInt();
                        if (count < 0 || count > RomTransferLimits.MAX_CATALOG_ENTRIES) {
                            throw new IllegalArgumentException("服务器 ROM 目录数量无效");
                        }
                        List<RomCatalogEntry> entries = new ArrayList<>(count);
                        for (int index = 0; index < count; index++) {
                            entries.add(new RomCatalogEntry(
                                    buffer.readUtf(RomTransferLimits.MAX_FILE_NAME_CHARS),
                                    buffer.readUtf(64),
                                    buffer.readVarInt(),
                                    buffer.readVarInt(),
                                    buffer.readVarInt(),
                                    cn.piq.fcarcade.rom.RomSaveMode.fromId(
                                            buffer.readVarInt())));
                        }
                        return new ArcadeLibraryPayload(
                                pos,
                                selected,
                                leaderboardEnabled,
                                entries, capabilities);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
