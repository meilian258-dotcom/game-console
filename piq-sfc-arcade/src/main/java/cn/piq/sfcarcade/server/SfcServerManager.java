// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.server;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.net.SfcNetwork;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import cn.piq.sfcarcade.rom.LegacySfcContentPaths;
import cn.piq.sfcarcade.world.SfcArcadeBlock;
import cn.piq.sfcarcade.world.SfcArcadeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public final class SfcServerManager {
    private static final double MAX_MACHINE_DISTANCE_SQUARED = 8.0 * 8.0;
    private static final int UPLOAD_TIMEOUT_TICKS = 20 * 30;
    private static final long MAX_ACTIVE_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ACTIVE_DOWNLOADS = 2;
    private static final int DOWNLOAD_TIMEOUT_TICKS = 20 * 30;
    private static final Map<MinecraftServer, SfcServerManager> MANAGERS = new IdentityHashMap<>();
    private static boolean registered;

    private final MinecraftServer server;
    private final SfcRomRepository repository;
    private final LegacySfcContentPaths contentPaths;
    private final ThreadPoolExecutor libraryWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> { var t = new Thread(r, "PIQ-SFC-Content"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID, LibraryWork<?>> libraryRequests = new HashMap<>();
    private Future<LegacySfcContentPaths.Report> preparation;
    private boolean contentPrepared, closed;
    private String preparationError = "";
    private int preparationFailedTick;
    private final Map<UUID, OutgoingDownload> downloads = new HashMap<>();
    private final Map<UUID, IncomingUpload> uploads = new HashMap<>();
    private final Map<UUID, Integer> uploadRateTicks = new HashMap<>();
    private final Map<UUID, Integer> uploadRateCounts = new HashMap<>();
    private final Map<MachineKey, Session> sessions = new HashMap<>();
    private final Map<UUID, MachineKey> memberships = new HashMap<>();
    private final ThreadPoolExecutor downloadWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),r->{var thread=new Thread(r,"PIQ-SFC-ROM-Download");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final DownloadSendGuard downloadSendGuard = new DownloadSendGuard();
    private boolean downloadPermissionActive;
    private int sessionCleanupTicks;

    private void pollPreparation() {
        if (preparation == null || !preparation.isDone()) return;
        try {
            var report = preparation.get();
            contentPrepared = true;
            SfcArcadeMod.LOGGER.info("[PIQ SFC] Content ready: {} (copied {}, reused {}, rejected {})",
                    contentPaths.root(), report.copied(), report.reused(), report.rejected());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException failure) {
            preparationError = readable(failure.getCause() == null ? failure : failure.getCause());
            preparationFailedTick = server.getTickCount();
            SfcArcadeMod.LOGGER.error("[PIQ SFC] Content preparation failed, originals retained: {}", preparationError);
        } finally { preparation = null; }
    }

    private boolean contentReady(ServerPlayer player) {
        pollPreparation();
        if (closed) return false;
        if (contentPrepared) return true;
        if (preparation == null && server.getTickCount() - preparationFailedTick >= 100) {
            try {
                preparation = libraryWorker.submit(contentPaths::prepare);
                preparationError = "";
            } catch (RejectedExecutionException rejected) {
                preparationError = "SFC content worker is busy or stopped";
                preparationFailedTick = server.getTickCount();
            }
        }
        if (preparationError.isEmpty()) player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.content_preparing"), true);
        else fail(player, "message.piq_sfc_arcade.content_failed", preparationError);
        return false;
    }

    private <T> void submitLibrary(ServerPlayer player, SfcArcadeBlockEntity machine, Callable<T> operation,
                                  java.util.function.Consumer<T> completion, Runnable cleanup) {
        if (closed || libraryRequests.size() >= 4 || libraryRequests.containsKey(player.getUUID())) {
            cleanup.run();
            player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.transfer_busy"), true);
            return;
        }
        var task = new ContentIoTask<>(operation);
        var work = new LibraryWork<>(player, machine, task, completion, cleanup);
        libraryRequests.put(player.getUUID(), work);
        try {
            libraryWorker.execute(task);
            player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.content_working"), true);
        } catch (RejectedExecutionException rejected) {
            task.cancel(); finishLibrary(work, false);
            player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.transfer_busy"), true);
        }
    }

    private <T> void finishLibrary(LibraryWork<T> work, boolean apply) {
        if (!apply) work.task.cancel();
        // Keep the slot and upload-memory reservation while interrupted IO actually unwinds.
        if (!work.task.done()) return;
        boolean valid = apply && !work.task.cancelled() && work.current();
        libraryRequests.remove(work.player.getUUID(), work);
        try {
            if (valid) {
                if (work.task.error() != null) fail(work.player, "message.piq_sfc_arcade.content_failed", readable(work.task.error()));
                else work.completion.accept(work.task.value());
            }
        } finally { work.cleanup.run(); }
    }

    private final class LibraryWork<T> {
        final ServerPlayer player;
        final Connection connection;
        final SfcArcadeBlockEntity machine;
        final String romHash, romName;
        final int startedTick;
        final ContentIoTask<T> task;
        final java.util.function.Consumer<T> completion;
        final Runnable cleanup;
        LibraryWork(ServerPlayer player, SfcArcadeBlockEntity machine, ContentIoTask<T> task,
                    java.util.function.Consumer<T> completion, Runnable cleanup) {
            this.player = player; connection = player.connection.getConnection(); this.machine = machine;
            romHash = machine.romHash(); romName = machine.romName(); startedTick = server.getTickCount();
            this.task = task; this.completion = completion; this.cleanup = cleanup;
        }
        boolean current() {
            BlockPos pos = machine.getBlockPos();
            return !closed && server.isSameThread() && libraryRequests.get(player.getUUID()) == this
                    && server.getPlayerList().getPlayer(player.getUUID()) == player
                    && player.connection.getConnection() == connection && connection.isConnected()
                    && !player.hasDisconnected() && player.isAlive() && !player.isSpectator() && player.hasPermissions(2)
                    && player.serverLevel() == machine.getLevel() && player.serverLevel().hasChunkAt(pos)
                    && !machine.isRemoved() && player.serverLevel().getBlockEntity(pos) == machine
                    && player.serverLevel().getBlockState(pos).getBlock() instanceof SfcArcadeBlock
                    && player.serverLevel().getWorldBorder().isWithinBounds(pos) && player.serverLevel().mayInteract(player, pos)
                    && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_MACHINE_DISTANCE_SQUARED
                    && romHash.equals(machine.romHash()) && romName.equals(machine.romName());
        }
    }

    private SfcServerManager(MinecraftServer server) {
        this.server = server;
        contentPaths = new LegacySfcContentPaths(server.getServerDirectory());
        repository = new SfcRomRepository(contentPaths.root());
        preparation = libraryWorker.submit(contentPaths::prepare);
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(SfcServerManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(SfcServerManager::onServerStopped);
    }

    public static void openLibrary(ServerPlayer player, BlockPos pos) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.op_only"), true);
            return;
        }
        SfcArcadeBlockEntity machine = validMachine(player, pos);
        if (machine == null) return;
        SfcServerManager manager = manager(player.server);
        if (!manager.contentReady(player)) return;
        SfcRomRepository repository = manager.repository;
        manager.submitLibrary(player, machine, () -> repository.list().stream()
                    .map(entry -> new SfcNetwork.CatalogEntry(
                            entry.fileName(), entry.sha256(), entry.size()))
                    .toList(), catalog -> SfcNetwork.sendLibrary(player,
                    new SfcNetwork.LibraryPayload(pos, machine.romHash(), catalog)), () -> {});
    }

    public static void selectRom(ServerPlayer player, BlockPos pos, String sha256) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.op_only"), true);
            return;
        }
        SfcArcadeBlockEntity machine = validMachine(player, pos);
        if (machine == null) return;
        if (manager(player.server).isOccupied(player.serverLevel().dimension(), pos)) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.configure_while_busy"), true);
            return;
        }
        SfcServerManager manager = manager(player.server);
        if (!manager.contentReady(player)) return;
        SfcRomRepository repository = manager.repository;
        manager.submitLibrary(player, machine, () -> repository.find(sha256), entry -> {
            if (entry == null) {
                fail(player, "message.piq_sfc_arcade.rom_missing_server", sha256);
                return;
            }
            if (manager.isOccupied(player.serverLevel().dimension(), pos)) {
                player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.configure_while_busy"), true);
                return;
            }
            machine.setRom(entry.sha256(), entry.fileName());
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.configured", entry.fileName()), true);
        }, () -> {});
    }

    public static void requestDownload(ServerPlayer player, String sha256) {
        requestDownload(player,sha256,player.connection.getConnection());
    }

    public static void requestDownload(ServerPlayer player,String sha256,Connection source) {
        if(player==null||player.getServer()==null||!player.getServer().isSameThread()
                ||source!=player.connection.getConnection()||!source.isConnected())return;
        SfcServerManager manager=MANAGERS.get(player.server);
        if(manager==null||manager.downloadPermissionActive)return;
        MachineKey key=manager.memberships.get(player.getUUID());Session session=manager.sessions.get(key);
        if(session==null||!session.owner.equals(player.getUUID())||manager.downloads.containsKey(player.getUUID()))return;
        try {
            boolean[] consulted={false};
            if(!session.downloadGate.admit(sha256,source,player.server.getTickCount(),()->{consulted[0]=true;return manager.downloadAllowed(player,session,true);})){ 
                if(consulted[0])manager.failDownload(session,"current session or interaction permission changed");
                return;
            }
            // Protection callbacks can replace a session, disconnect, or fill the bounded worker.
            if(!manager.downloadFacts(player,session)){session.downloadGate.complete();return;}
            if(manager.downloads.size()>=MAX_ACTIVE_DOWNLOADS){
                session.downloadGate.complete();player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.transfer_busy"),true);manager.release(session.key,true);return;
            }
            Future<byte[]> future=manager.downloadWorker.submit(()->manager.repository.readNamedVerified(session.romName,session.romHash));
            manager.downloads.put(player.getUUID(),new OutgoingDownload(session,future,player.server.getTickCount()));
        } catch (RuntimeException exception) {
            session.downloadGate.complete();
            if(manager.sessions.get(session.key)==session)manager.release(session.key,true);
            fail(player, "message.piq_sfc_arcade.download_failed", exception.getMessage());
        }
    }

    private boolean downloadFacts(ServerPlayer player,Session session){
        return player!=null&&server.isSameThread()&&server.getPlayerList().getPlayer(session.owner)==player
                &&player.getUUID().equals(session.owner)&&player.connection.getConnection()==session.connection&&session.connection.isConnected()
                &&player.isAlive()&&!player.isSpectator()&&!player.hasDisconnected()
                &&sessions.get(session.key)==session&&session.key.equals(memberships.get(session.owner))
                &&player.serverLevel()==session.machine.getLevel()&&player.serverLevel().dimension().equals(session.key.dimension())
                &&player.serverLevel().hasChunkAt(session.key.pos())&&!session.machine.isRemoved()
                &&player.serverLevel().getBlockEntity(session.key.pos())==session.machine
                &&player.serverLevel().getBlockState(session.key.pos()).getBlock() instanceof SfcArcadeBlock
                &&session.romHash.equals(session.machine.romHash())&&session.romName.equals(session.machine.romName())
                &&player.distanceToSqr(Vec3.atCenterOf(session.key.pos()))<=MAX_MACHINE_DISTANCE_SQUARED
                &&player.serverLevel().getWorldBorder().isWithinBounds(session.key.pos())
                &&player.serverLevel().mayInteract(player,session.key.pos());
    }
    private boolean downloadAllowed(ServerPlayer player,Session session,boolean consultProtection){
        if(!downloadFacts(player,session)||downloadPermissionActive)return false;
        if(!consultProtection)return true;
        var held=player.getMainHandItem();downloadPermissionActive=true;
        try{
            var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,session.key.pos(),
                    new BlockHitResult(Vec3.atCenterOf(session.key.pos()),Direction.UP,session.key.pos(),false)));
            return !event.isCanceled()&&event.getUseBlock()!=TriState.FALSE&&event.getUseItem()!=TriState.FALSE
                    &&player.getMainHandItem()==held&&downloadFacts(player,session);
        }catch(RuntimeException|LinkageError denied){return false;}
        finally{downloadPermissionActive=false;}
    }

    public static void beginUpload(ServerPlayer player, SfcNetwork.UploadStartPayload payload) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.op_only"), true);
            return;
        }
        SfcArcadeBlockEntity machine = validMachine(player, payload.pos());
        if (machine == null) return;
        SfcServerManager manager = manager(player.server);
        if (!manager.contentReady(player)) return;
        if (manager.isOccupied(player.serverLevel().dimension(), payload.pos())) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.configure_while_busy"), true);
            return;
        }
        try {
            validateUploadMetadata(payload);
            if (manager.uploads.containsKey(player.getUUID()) || manager.libraryRequests.containsKey(player.getUUID())) {
                player.displayClientMessage(Component.translatable(
                        "message.piq_sfc_arcade.transfer_busy"), true);
                return;
            }
            if (manager.activeUploadBytes() + payload.totalBytes() > MAX_ACTIVE_UPLOAD_BYTES) {
                fail(player, "message.piq_sfc_arcade.upload_failed",
                        "server upload memory limit reached");
                return;
            }
            manager.uploads.put(player.getUUID(), new IncomingUpload(
                    player.connection.getConnection(), machine,
                    payload.pos().immutable(),
                    payload.fileName(),
                    payload.sha256(),
                    new byte[payload.totalBytes()],
                    player.server.getTickCount()));
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.upload_started", payload.fileName()), true);
        } catch (IOException | IllegalArgumentException exception) {
            fail(player, "message.piq_sfc_arcade.upload_failed", readable(exception));
        }
    }

    public static void acceptUploadChunk(
            ServerPlayer player,
            SfcNetwork.UploadChunkPayload payload
    ) {
        SfcServerManager manager = manager(player.server);
        UUID playerId = player.getUUID();
        IncomingUpload upload = manager.uploads.get(playerId);
        if (upload != null && upload.submitted) return; // Keep the quota until the worker has actually stopped.
        if (!player.hasPermissions(2)) {
            manager.removeUpload(playerId);
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.op_only"), true);
            return;
        }
        if (upload == null || !upload.sha256.equals(payload.sha256())) return;
        if (upload.connection != player.connection.getConnection() || !upload.connection.isConnected()
                || upload.machine.getLevel() != player.serverLevel()
                || !player.serverLevel().hasChunkAt(upload.pos)
                || player.serverLevel().getBlockEntity(upload.pos) != upload.machine) {
            manager.removeUpload(playerId); return;
        }
        try {
            manager.checkUploadRate(playerId, player.server.getTickCount());
            byte[] data = payload.data();
            if (data.length == 0
                    || data.length > SfcNetwork.CHUNK_BYTES
                    || payload.offset() != upload.offset
                    || payload.offset() + data.length > upload.bytes.length) {
                throw new IOException("invalid SFC upload chunk");
            }
            System.arraycopy(data, 0, upload.bytes, upload.offset, data.length);
            upload.offset += data.length;
            upload.lastActivityTick = player.server.getTickCount();
            if (!upload.complete()) return;

            SfcArcadeBlockEntity machine = validMachine(player, upload.pos);
            if (machine == null) {
                manager.removeUpload(playerId);
                return;
            }
            if (manager.isOccupied(player.serverLevel().dimension(), upload.pos)) {
                manager.removeUpload(playerId);
                player.displayClientMessage(Component.translatable(
                        "message.piq_sfc_arcade.configure_while_busy"), true);
                return;
            }
            if (upload.submitted) return;
            upload.submitted = true;
            SfcRomRepository repository = manager.repository;
            String fileName = upload.fileName, hash = upload.sha256;
            byte[] bytes = upload.bytes;
            manager.submitLibrary(player, machine, () -> repository.storeVerified(
                    fileName, hash, bytes), stored -> {
                if (manager.uploads.get(playerId) != upload) return;
                if (!manager.isOccupied(player.serverLevel().dimension(), upload.pos)) {
                    machine.setRom(stored.sha256(), stored.fileName());
                    player.displayClientMessage(Component.translatable(
                            "message.piq_sfc_arcade.uploaded", stored.fileName()), true);
                } else player.displayClientMessage(Component.translatable("message.piq_sfc_arcade.configure_while_busy"), true);
            }, () -> { if (manager.uploads.get(playerId) == upload) manager.removeUpload(playerId); });
        } catch (IOException | IllegalArgumentException exception) {
            manager.removeUpload(playerId);
            fail(player, "message.piq_sfc_arcade.upload_failed", readable(exception));
        }
    }

    private static SfcArcadeBlockEntity validMachine(ServerPlayer player, BlockPos pos) {
        if (player.hasDisconnected() || !player.connection.getConnection().isConnected()
                || !player.isAlive() || player.isSpectator() || !player.serverLevel().hasChunkAt(pos)
                || !player.serverLevel().getWorldBorder().isWithinBounds(pos) || !player.serverLevel().mayInteract(player, pos)
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_MACHINE_DISTANCE_SQUARED
                || !(player.serverLevel().getBlockState(pos).getBlock() instanceof SfcArcadeBlock)
                || !(player.serverLevel().getBlockEntity(pos) instanceof SfcArcadeBlockEntity machine)) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.machine_unavailable"), true);
            return null;
        }
        return machine;
    }

    public static void requestSession(ServerPlayer player, BlockPos pos, boolean stop) {
        if(player.getServer()==null||!player.getServer().isSameThread()||player.hasDisconnected()
                ||!player.connection.getConnection().isConnected()||player.getServer().getPlayerList().getPlayer(player.getUUID())!=player)return;
        SfcServerManager manager = manager(player.server);
        MachineKey requested = new MachineKey(player.serverLevel().dimension(), pos.immutable());
        if (stop) {
            manager.stopSession(player, requested);
            return;
        }
        if (!manager.contentReady(player)) { manager.sendInactive(player, pos); return; }
        SfcArcadeBlockEntity machine = validMachine(player, pos);
        if (machine == null) {
            manager.sendInactive(player, pos);
            return;
        }
        if (machine.romHash().isBlank()) {
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.not_configured"), true);
            manager.sendInactive(player, requested.pos());
            return;
        }

        Session existing = manager.sessions.get(requested);
        if(existing!=null&&(!existing.connection.isConnected()||existing.owner.equals(player.getUUID())&&existing.connection!=player.connection.getConnection())){
            manager.release(requested,false);existing=null;
        }
        if (existing != null) {
            if (existing.owner.equals(player.getUUID())) {
                manager.sendActive(player, existing, true);
            } else {
                manager.sendActive(player, existing, false);
                player.displayClientMessage(Component.translatable(
                        "message.piq_sfc_arcade.occupied", existing.playerName), true);
            }
            return;
        }

        MachineKey previous = manager.memberships.get(player.getUUID());
        if (previous != null && !previous.equals(requested)) {
            manager.release(previous, true);
        }

        Session session = new Session(
                requested,
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                machine.romHash(),
                machine.romName(),player.connection.getConnection(),machine);
        manager.sessions.put(requested, session);
        manager.memberships.put(player.getUUID(), requested);
        SfcOccupancyDisplay.refresh(player.server, requested.dimension(), requested.pos(),
                session.playerName);
        manager.sendActive(player, session, true);
    }

    private void stopSession(ServerPlayer player, MachineKey requested) {
        Session session = sessions.get(requested);
        if (session == null) {
            sendInactive(player, requested.pos());
            return;
        }
        if (!session.owner.equals(player.getUUID())) {
            sendActive(player, session, false);
            player.displayClientMessage(Component.translatable(
                    "message.piq_sfc_arcade.occupied", session.playerName), true);
            return;
        }
        release(requested, true);
    }

    private static SfcServerManager manager(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, SfcServerManager::new);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        SfcServerManager manager = MANAGERS.get(event.getServer());
        if (manager != null) manager.tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        pollPreparation();
        for (LibraryWork<?> work : List.copyOf(libraryRequests.values())) {
            if (tick - work.startedTick > 20 * 60 || !work.current()) {
                if (!work.task.cancelled() && work.current()) fail(work.player, "message.piq_sfc_arcade.content_failed", "SFC content operation timed out");
                finishLibrary(work, false); continue;
            }
            if (work.task.done()) finishLibrary(work, true);
        }
        var uploadIterator = uploads.entrySet().iterator();
        while (uploadIterator.hasNext()) {
            var pending = uploadIterator.next();
            if (!pending.getValue().submitted && (server.getPlayerList().getPlayer(pending.getKey()) == null
                    || tick - pending.getValue().lastActivityTick > UPLOAD_TIMEOUT_TICKS)) {
                uploadIterator.remove();
                uploadRateTicks.remove(pending.getKey());
                uploadRateCounts.remove(pending.getKey());
            }
        }

        for(OutgoingDownload transfer:List.copyOf(downloads.values())){
            UUID owner=transfer.session.owner;ServerPlayer player=server.getPlayerList().getPlayer(owner);
            if(downloads.get(owner)!=transfer)continue;
            if(tick-transfer.startedTick>DOWNLOAD_TIMEOUT_TICKS||!downloadFacts(player,transfer.session)){
                removeDownload(transfer);failDownload(transfer.session,"download expired or current session changed");continue;
            }
            if(transfer.bytes==null){
                if(!transfer.future.isDone())continue;
                try{
                    byte[] bytes=transfer.future.get();
                    if(!downloadFacts(player,transfer.session)||downloads.get(owner)!=transfer){removeDownload(transfer);continue;}
                    transfer.bytes=bytes;
                    if(!sendDownloadPacket(player,transfer,()->SfcNetwork.sendDownloadStart(player,
                            new SfcNetwork.DownloadStartPayload(transfer.session.romName,transfer.session.romHash,bytes.length))))continue;
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();removeDownload(transfer);continue;}
                catch(ExecutionException|CancellationException failure){removeDownload(transfer);failDownload(transfer.session,readable(failure));continue;}
            }
            for (int chunk = 0;
                 chunk < SfcNetwork.CHUNKS_PER_TICK && downloads.get(owner)==transfer && !transfer.complete();
                 chunk++) {
                int offset = transfer.offset;
                int length = Math.min(SfcNetwork.CHUNK_BYTES, transfer.bytes.length - offset);
                byte[] data = java.util.Arrays.copyOfRange(
                        transfer.bytes, offset, offset + length);
                if(!sendDownloadPacket(player,transfer,()->SfcNetwork.sendDownloadChunk(player,
                        new SfcNetwork.DownloadChunkPayload(transfer.session.romHash,offset,data))))break;
                transfer.offset += length;
            }
            if (downloads.get(owner)==transfer && transfer.complete()) removeDownload(transfer);
        }

        sessionCleanupTicks++;
        if (sessionCleanupTicks < 20) return;
        sessionCleanupTicks = 0;
        for (Session session : sessions.values().toArray(Session[]::new)) {
            ServerPlayer owner = server.getPlayerList().getPlayer(session.owner);
            Level level = server.getLevel(session.key.dimension());
            boolean invalid = owner == null
                    || level == null
                    || owner.connection.getConnection()!=session.connection||!session.connection.isConnected()
                    || level.getBlockEntity(session.key.pos())!=session.machine
                    || !owner.serverLevel().dimension().equals(session.key.dimension())
                    || !(level.getBlockState(session.key.pos()).getBlock()
                    instanceof SfcArcadeBlock)
                    || owner.distanceToSqr(
                    session.key.pos().getX() + 0.5,
                    session.key.pos().getY() + 0.5,
                    session.key.pos().getZ() + 0.5) > 64.0 * 64.0;
            if (invalid) {
                release(session.key, owner != null);
            } else {
                SfcOccupancyDisplay.refresh(server, session.key.dimension(),
                        session.key.pos(), session.playerName);
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SfcServerManager manager = MANAGERS.remove(event.getServer());
        if (manager != null) {
            manager.closed = true;
            if (manager.preparation != null) manager.preparation.cancel(true);
            for (LibraryWork<?> work : List.copyOf(manager.libraryRequests.values())) manager.finishLibrary(work, false);
            manager.libraryWorker.shutdownNow();
            // IO tasks contain only immutable names/paths/bytes, never player or world callbacks.
            manager.libraryRequests.clear(); manager.uploads.clear();
            for(OutgoingDownload transfer:List.copyOf(manager.downloads.values()))manager.removeDownload(transfer);
            manager.downloadWorker.shutdownNow();
            for (Session session : manager.sessions.values().toArray(Session[]::new)) {
                SfcOccupancyDisplay.remove(event.getServer(), session.key.dimension(),
                        session.key.pos());
            }
        }
    }

    private boolean isOccupied(ResourceKey<Level> dimension, BlockPos pos) {
        return sessions.containsKey(new MachineKey(dimension, pos.immutable()));
    }

    private void release(MachineKey key, boolean notifyOwner) {
        Session removed = sessions.remove(key);
        if (removed == null) return;
        memberships.remove(removed.owner, key);
        OutgoingDownload download=downloads.get(removed.owner);if(download!=null&&download.session==removed)removeDownload(download);
        SfcOccupancyDisplay.remove(server, key.dimension(), key.pos());
        if (notifyOwner) {
            ServerPlayer owner = server.getPlayerList().getPlayer(removed.owner);
            if (owner != null&&owner.connection.getConnection()==removed.connection) sendInactive(owner, key.pos());
        }
    }

    private void sendActive(ServerPlayer player, Session session, boolean owner) {
        SfcNetwork.sendSession(player, new SfcNetwork.SessionStatePayload(
                session.key.pos(),
                session.id,
                true,
                owner,
                session.playerName,
                session.romHash,
                session.romName));
    }

    private void sendInactive(ServerPlayer player, BlockPos pos) {
        SfcNetwork.sendSession(player, new SfcNetwork.SessionStatePayload(
                pos,
                SfcNetwork.SessionStatePayload.INACTIVE_SESSION,
                false,
                false,
                "",
                "",
                ""));
    }

    private static void fail(ServerPlayer player, String key, Object argument) {
        player.displayClientMessage(Component.translatable(key, argument), false);
        SfcArcadeMod.LOGGER.warn("[PIQ SFC] {}: {}", key, argument);
    }

    private static void validateUploadMetadata(SfcNetwork.UploadStartPayload payload)
            throws IOException {
        if (payload.totalBytes() <= 0
                || payload.totalBytes() > SfcRomRepository.MAX_SOURCE_BYTES
                || !payload.sha256().matches("[0-9a-f]{64}")) {
            throw new IOException("invalid SFC upload metadata");
        }
        String name = payload.fileName().strip();
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isBlank()
                || name.length() > 128
                || name.chars().anyMatch(Character::isISOControl)
                || !Path.of(name).getFileName().toString().equals(name)
                || (!lower.endsWith(".sfc") && !lower.endsWith(".smc"))) {
            throw new IOException("invalid SFC ROM file name");
        }
    }

    private void checkUploadRate(UUID playerId, int tick) throws IOException {
        Integer previousTick = uploadRateTicks.put(playerId, tick);
        if (previousTick == null || previousTick != tick) {
            uploadRateCounts.put(playerId, 0);
        }
        int count = uploadRateCounts.merge(playerId, 1, Integer::sum);
        if (count > SfcNetwork.CHUNKS_PER_TICK * 2) {
            throw new IOException("SFC upload rate limit exceeded");
        }
    }

    private long activeUploadBytes() {
        return uploads.values().stream().mapToLong(upload -> upload.bytes.length).sum();
    }

    private void removeUpload(UUID playerId) {
        uploads.remove(playerId);
        uploadRateTicks.remove(playerId);
        uploadRateCounts.remove(playerId);
    }

    private static String readable(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private void removeDownload(OutgoingDownload transfer){
        downloads.remove(transfer.session.owner,transfer);transfer.future.cancel(true);downloadWorker.purge();transfer.bytes=null;transfer.session.downloadGate.complete();
    }

    /** Each actual packet is a fresh permission transaction, including IO completion. */
    private boolean sendDownloadPacket(ServerPlayer player,OutgoingDownload transfer,Runnable send){
        String reason="current session or interaction permission changed";
        try{
            if(downloadSendGuard.run(
                    ()->downloads.get(transfer.session.owner)==transfer&&transfer.bytes!=null&&downloadFacts(player,transfer.session),
                    ()->downloadAllowed(player,transfer.session,true),send))return true;
        }catch(RuntimeException|LinkageError failure){reason=readable(failure);}
        removeDownload(transfer);failDownload(transfer.session,reason);return false;
    }

    private void failDownload(Session session,String reason){
        // A protection callback may have installed a replacement. Never close or notify that transaction.
        if(sessions.get(session.key)!=session)return;
        release(session.key,true);
        ServerPlayer player=server.getPlayerList().getPlayer(session.owner);
        if(player!=null&&player.connection.getConnection()==session.connection&&session.connection.isConnected()
                &&!player.hasDisconnected())fail(player,"message.piq_sfc_arcade.download_failed",reason);
    }

    /** Pure callback order shared by the actual packet path and its regression probe. */
    static final class DownloadSendGuard {
        private boolean active;
        boolean run(java.util.function.BooleanSupplier facts,java.util.function.BooleanSupplier permission,Runnable send){
            if(active)return false;
            active=true;
            try{
                if(!facts.getAsBoolean()||!permission.getAsBoolean()||!facts.getAsBoolean())return false;
                send.run();return true;
            }finally{active=false;}
        }
    }

    private static final class OutgoingDownload {
        private final Session session;
        private final Future<byte[]> future;
        private final int startedTick;
        private byte[] bytes;
        private int offset;

        private OutgoingDownload(Session session,Future<byte[]> future,int startedTick) {
            this.session=session;this.future=future;this.startedTick=startedTick;
        }

        private boolean complete() {
            return offset >= bytes.length;
        }
    }

    private static final class IncomingUpload {
        private final Connection connection;
        private final SfcArcadeBlockEntity machine;
        private final BlockPos pos;
        private final String fileName;
        private final String sha256;
        private final byte[] bytes;
        private int offset;
        private int lastActivityTick;
        private boolean submitted;

        private IncomingUpload(
                Connection connection, SfcArcadeBlockEntity machine,
                BlockPos pos,
                String fileName,
                String sha256,
                byte[] bytes,
                int lastActivityTick
        ) {
            this.connection = connection; this.machine = machine;
            this.pos = pos;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.lastActivityTick = lastActivityTick;
        }

        private boolean complete() {
            return offset >= bytes.length;
        }
    }

    private record MachineKey(ResourceKey<Level> dimension, BlockPos pos) {
        private MachineKey {
            pos = pos.immutable();
        }
    }

    private static final class Session {
        private final MachineKey key;
        private final UUID id;
        private final UUID owner;
        private final String playerName;
        private final String romHash;
        private final String romName;
        private final Connection connection;
        private final SfcArcadeBlockEntity machine;
        private final SfcDownloadGate downloadGate;

        private Session(
                MachineKey key,
                UUID id,
                UUID owner,
                String playerName,
                String romHash,
                String romName,Connection connection,SfcArcadeBlockEntity machine
        ) {
            this.key = key;
            this.id = id;
            this.owner = owner;
            this.playerName = playerName;
            this.romHash = romHash;
            this.romName = romName;
            this.connection=connection;this.machine=machine;this.downloadGate=new SfcDownloadGate(romHash,connection);
        }
    }
}
