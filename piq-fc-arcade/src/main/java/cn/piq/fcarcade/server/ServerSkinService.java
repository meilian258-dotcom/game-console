package cn.piq.fcarcade.server;

import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.SkinDownloadChunkPayload;
import cn.piq.fcarcade.SkinDownloadStartPayload;
import cn.piq.fcarcade.SkinLibraryPayload;
import cn.piq.fcarcade.SkinSelectPayload;
import cn.piq.fcarcade.SkinUploadChunkPayload;
import cn.piq.fcarcade.SkinUploadStartPayload;
import cn.piq.fcarcade.skin.SkinDescriptor;
import cn.piq.fcarcade.skin.SkinImageCodec;
import cn.piq.fcarcade.skin.SkinTransferBuffer;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import cn.piq.fcarcade.skin.SkinTransferBudget;
import cn.piq.fcarcade.world.LegacyFcArcadeBlock;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class ServerSkinService {
    private static final double MAX_DISTANCE_SQUARED = 8.0D * 8.0D;
    private static final Map<MinecraftServer, Service> SERVICES =
            new WeakHashMap<>();

    private ServerSkinService() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerSkinService::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerSkinService::onServerStopped);
    }

    public static void openLibrary(ServerPlayer player, BlockPos pos) {
        Service service = service(player);
        if (service != null) service.openLibrary(player, pos);
    }

    public static void beginUpload(
            ServerPlayer player,
            SkinUploadStartPayload payload
    ) {
        Service service = service(player);
        if (service != null) service.beginUpload(player, payload);
    }

    public static void acceptUploadChunk(
            ServerPlayer player,
            SkinUploadChunkPayload payload
    ) {
        Service service = service(player);
        if (service != null) service.acceptUploadChunk(player, payload);
    }

    public static void select(
            ServerPlayer player,
            SkinSelectPayload payload
    ) {
        Service service = service(player);
        if (service != null) service.select(player, payload);
    }

    public static void requestDownload(ServerPlayer player, String sha256) {
        Service service = service(player);
        if (service != null) service.requestDownload(player, sha256);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Service existing = SERVICES.get(event.getServer());
        if (existing != null) existing.tick(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SERVICES.remove(event.getServer());
    }

    private static Service service(ServerPlayer player) {
        try { return SERVICES.computeIfAbsent(player.getServer(), Service::new); }
        catch (java.io.UncheckedIOException error) {
            cn.piq.fcarcade.FcArcadeMod.LOGGER.error("[PIQ FC] 服务器皮肤目录初始化失败；旧数据保留，拒绝使用空库", error);
            player.sendSystemMessage(Component.literal("FC 服务器皮肤目录不可用；旧数据已保留，请管理员检查日志。"));
            return null;
        }
    }

    private static final class Service {
        private final ServerSkinLibrary library;
        private final Map<UUID, IncomingUpload> uploads = new HashMap<>();
        private final Map<UUID, OutgoingDownload> downloads = new java.util.LinkedHashMap<>();
        private final Map<UUID, Integer> downloadMissTicks = new HashMap<>();
        private int lookupTick = Integer.MIN_VALUE;
        private int lookups;

        private Service(MinecraftServer server) {
            library = new ServerSkinLibrary(
                    cn.piq.fcarcade.storage.FcStoragePaths.prepareUnchecked(server.getServerDirectory(),
                            cn.piq.fcarcade.storage.FcStoragePaths.Area.SHARED_SKINS));
        }

        private void openLibrary(ServerPlayer player, BlockPos pos) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_op_only"));
                return;
            }
            LegacyFcArcadeBlockEntity machine = validMachine(player, pos);
            if (machine == null) return;
            var catalog = library.catalog();
            if (!machine.skinHash().isEmpty()) {
                catalog.stream().filter(skin -> skin.sha256().equals(machine.skinHash())
                        && !skin.compatible()).findFirst().ifPresent(skin -> player.sendSystemMessage(
                        Component.translatable("message.piq_fc_arcade.skin_incompatible_preserved")));
            }
            FcNetwork.sendSkinLibrary(player, new SkinLibraryPayload(
                    machine.getBlockPos(),
                    machine.skinHash(),
                    catalog));
        }

        private void beginUpload(
                ServerPlayer player,
                SkinUploadStartPayload payload
        ) {
            if (!player.hasPermissions(2)) return;
            LegacyFcArcadeBlockEntity machine =
                    validMachine(player, payload.blockPos());
            if (machine == null) return;
            long retainedBytes = uploads.values().stream().mapToLong(value -> value.buffer.totalBytes()).sum();
            if (uploads.containsKey(player.getUUID()) || !SkinTransferBudget.canStart(
                    uploads.size(), retainedBytes, payload.totalBytes())) {
                player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.skin_transfer_busy"));
                return;
            }
            uploads.put(
                    player.getUUID(),
                    new IncomingUpload(
                            new SkinTransferBuffer(
                                    payload.name(),
                                    payload.sha256(),
                                    payload.totalBytes()),
                            machine.getBlockPos().immutable(),
                            player.level().dimension(),
                            player.getServer().getTickCount()));
        }

        private void acceptUploadChunk(
                ServerPlayer player,
                SkinUploadChunkPayload payload
        ) {
            IncomingUpload upload = uploads.get(player.getUUID());
            if (upload == null || !player.hasPermissions(2)
                    || !upload.buffer.sha256().equals(payload.sha256())) {
                return;
            }
            LegacyFcArcadeBlockEntity machine =
                    validMachine(player, upload.blockPos);
            if (machine == null || player.level().dimension() != upload.dimension) {
                uploads.remove(player.getUUID());
                return;
            }
            try {
                // Client ticks and server ticks are not synchronized: legitimate
                // chunks can arrive together after low TPS or network backlog.
                // Admission already reserves bounded buffers; append enforces
                // per-chunk size, contiguous offsets and the declared total.
                upload.buffer.append(payload.offset(), payload.data());
                upload.lastActivityTick = player.getServer().getTickCount();
                if (!upload.buffer.complete()) return;
                byte[] png = upload.buffer.completedBytes();
                SkinDescriptor stored = library.store(
                        upload.buffer.name(),
                        upload.buffer.sha256(),
                        png);
                machine.setSkin(stored.sha256(), stored.name());
                uploads.remove(player.getUUID());
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_uploaded",
                        stored.name()));
                openLibrary(player, machine.getBlockPos());
            } catch (IllegalArgumentException | IllegalStateException error) {
                uploads.remove(player.getUUID());
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_upload_failed",
                        readable(error)));
            }
        }

        private void select(ServerPlayer player, SkinSelectPayload payload) {
            if (!player.hasPermissions(2)) return;
            LegacyFcArcadeBlockEntity machine =
                    validMachine(player, payload.blockPos());
            if (machine == null) return;
            if (payload.sha256().isEmpty()) {
                machine.setSkin("", "");
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_defaulted"));
                return;
            }
            ServerSkinLibrary.SkinData data =
                    library.find(payload.sha256());
            if (data == null) {
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_missing"));
                return;
            }
            if (!data.descriptor().compatible()) {
                player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.skin_incompatible_preserved"));
                return;
            }
            machine.setSkin(
                    data.descriptor().sha256(),
                    data.descriptor().name());
            player.sendSystemMessage(Component.translatable(
                    "message.piq_fc_arcade.skin_selected",
                    data.descriptor().name()));
        }

        private void requestDownload(ServerPlayer player, String sha256) {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || downloads.containsKey(player.getUUID())) return;
            int tick = player.getServer().getTickCount();
            Integer missed = downloadMissTicks.get(player.getUUID());
            if (missed != null && tick - missed < 40) return;
            if (lookupTick != tick) { lookupTick = tick; lookups = 0; }
            long retained = downloads.values().stream().mapToLong(value -> value.data.descriptor().size()).sum();
            if (++lookups > 8 || !SkinTransferBudget.canStart(downloads.size(), retained, 1)) return;
            ServerSkinLibrary.SkinData data = library.find(sha256);
            if (data == null) { downloadMissTicks.put(player.getUUID(), tick); return; }
            if (!SkinTransferBudget.canStart(downloads.size(), retained, data.descriptor().size())) return;
            downloads.put(
                    player.getUUID(),
                    new OutgoingDownload(data, 0));
            FcNetwork.startSkinDownload(
                    player,
                    new SkinDownloadStartPayload(
                            data.descriptor().name(),
                            data.descriptor().sha256(),
                            data.descriptor().size()));
        }

        private void tick(MinecraftServer server) {
            uploads.entrySet().removeIf(entry -> server.getPlayerList().getPlayer(entry.getKey()) == null
                    || server.getTickCount() - entry.getValue().lastActivityTick > SkinTransferLimits.TRANSFER_TIMEOUT_TICKS);
            downloadMissTicks.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            int globalChunks = SkinTransferLimits.GLOBAL_CHUNKS_PER_TICK;
            for (UUID id : java.util.List.copyOf(downloads.keySet())) {
                if (globalChunks == 0) break;
                ServerPlayer player =
                        server.getPlayerList().getPlayer(id);
                if (player == null) {
                    downloads.remove(id);
                    continue;
                }
                OutgoingDownload download = downloads.remove(id);
                int length = download.data.descriptor().size();
                int offset = download.offset;
                for (int chunk = 0;
                     chunk < SkinTransferLimits.CHUNKS_PER_TICK
                             && globalChunks > 0 && offset < length;
                     chunk++) {
                    int end = Math.min(
                            offset + SkinTransferLimits.CHUNK_BYTES,
                            length);
                    FcNetwork.sendSkinChunk(
                            player,
                            new SkinDownloadChunkPayload(
                                    download.data.descriptor().sha256(),
                                    offset,
                                    download.data.copyBytes(offset, end)));
                    offset = end;
                    globalChunks--;
                }
                if (offset < length) downloads.put(id, new OutgoingDownload(download.data, offset));
            }
        }

        private LegacyFcArcadeBlockEntity validMachine(
                ServerPlayer player,
                BlockPos pos
        ) {
            if (!player.serverLevel().hasChunkAt(pos)
                    || !player.serverLevel().mayInteract(player, pos)) return null;
            if (player.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D) > MAX_DISTANCE_SQUARED) {
                return null;
            }
            var block = player.serverLevel().getBlockState(pos).getBlock();
            boolean dual = block instanceof cn.piq.fcarcade.world.DualCabinetBlock;
            if (dual && !cn.piq.fcarcade.world.DualCabinetStructure.complete(player.serverLevel(), pos)) return null;
            if (!(block instanceof LegacyFcArcadeBlock) && !dual) {
                player.sendSystemMessage(Component.translatable(
                        "message.piq_fc_arcade.skin_legacy_only"));
                return null;
            }
            if (player.serverLevel().getBlockEntity(pos)
                    instanceof LegacyFcArcadeBlockEntity machine) {
                return machine;
            }
            player.sendSystemMessage(Component.translatable(
                    "message.piq_fc_arcade.skin_replace_machine"));
            return null;
        }

        private static String readable(Throwable error) {
            String message = error.getMessage();
            return message == null
                    ? error.getClass().getSimpleName()
                    : message;
        }
    }

    private static final class IncomingUpload {
        private final SkinTransferBuffer buffer;
        private final BlockPos blockPos;
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        private int lastActivityTick;

        private IncomingUpload(SkinTransferBuffer buffer, BlockPos blockPos,
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int tick) {
            this.buffer = buffer;
            this.blockPos = blockPos;
            this.dimension = dimension;
            this.lastActivityTick = tick;
        }
    }

    private record OutgoingDownload(
            ServerSkinLibrary.SkinData data,
            int offset
    ) {
    }
}
