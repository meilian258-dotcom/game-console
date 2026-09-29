package cn.piq.fcarcade.server;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.fcarcade.access.PlayerContentPolicy;
import cn.piq.fcarcade.home.CartridgeNetwork.Reply;
import cn.piq.fcarcade.home.CartridgeNetwork.Request;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomSaveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Authoritative held-item transactions. Inventory/network calls always remain on the server thread. */
public final class ServerCartridgeService {
    private static final Map<MinecraftServer, State> STATES = new HashMap<>();
    private static final CartridgeTransferBudget BUDGET = new CartridgeTransferBudget();
    private static final ThreadLocal<Boolean> CHECKING_COMPUTER = ThreadLocal.withInitial(() -> false);
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread t = new Thread(task, "PIQ-FC-cartridge-IO"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private static boolean registered;
    private ServerCartridgeService() {}
    public static boolean editing(ServerPlayer player,ItemStack stack){var state=STATES.get(player.getServer());var session=state==null?null:state.sessions.get(player.getUUID());return session!=null&&session.stack==stack||CartridgeSaveService.editing(player,stack);}
    public static void register() {
        if (registered) return;
        registered = true;
        ServerCartridgeAssemblyService.register();
        CartridgeSaveService.register();
        NeoForge.EVENT_BUS.addListener(ServerCartridgeService::tick);
        NeoForge.EVENT_BUS.addListener(ServerCartridgeService::logout);
        NeoForge.EVENT_BUS.addListener(ServerCartridgeService::stopped);
    }
    public static void openAt(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        if (CHECKING_COMPUTER.get()) return; // A protection listener cannot recursively mint an edit lease.
        CartridgeComputerBlockEntity computer = loadedComputer(player, pos);
        if (computer == null) { HomeFeedback.show(player, "computer_denied"); return; }
        var station = new CartridgeComputerBinding(computer.computerId(), player.serverLevel().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ());
        if (!computerPermitted(player, hand, station, computer)) { HomeFeedback.show(player, "computer_denied"); return; }
        ItemStack stack = player.getItemInHand(hand);
        if (!FcCartridgeData.isCartridge(stack) || stack.getCount() != 1 || !FcCartridgeData.supportsAssembly(stack)
                || player.containerMenu != player.inventoryMenu) {
            HomeFeedback.show(player, "computer_invalid_card"); return;
        }
        State state = state(player);
        if (state == null) return;
        int tick = player.getServer().getTickCount();
        if (tick - state.lastOpen.getOrDefault(player.getUUID(), tick - 10) < 10) return;
        state.lastOpen.put(player.getUUID(), tick);
        state.cancel(player.getUUID(), "");
        if (state.sessions.size() >= 32) {
            HomeFeedback.show(player, "computer_busy"); return;
        }
        UUID cartridge;
        try { cartridge = FcCartridgeData.ensureIdentity(stack); }
        catch (IllegalArgumentException invalid) {
            HomeFeedback.show(player, "computer_invalid_card"); return;
        }
        if (!uniqueCard(player, stack, cartridge)) { HomeFeedback.show(player, "computer_invalid_card"); return; }
        if(FcCartridgeData.storedSaveMode(stack)<0)FcCartridgeData.setSaveMode(stack,state.library.saveMode(FcCartridgeData.romSha(stack)));
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
        Session session = new Session(player.getUUID(), new CartridgeEditBinding(UUID.randomUUID(), cartridge,
                hand == InteractionHand.MAIN_HAND ? 0 : 1, slot), stack, station, computer, tick);
        state.sessions.put(player.getUUID(), session);
        if (!state.valid(player, session, session.target)) { state.cancel(player.getUUID(), "电脑编辑授权已失效，请重新右键电脑。"); return; }
        player.inventoryMenu.broadcastChanges();
        state.send(player, session, CartridgeNetwork.OPEN, "", "电脑已授权：离开 5 格、换卡/换槽、拆机或关闭界面即取消。", state.library.homeCatalog());
        state.refreshCovers(player, session);
    }
    /** Mint an independent save-manager grant only from the actual current computer edit lease. */
    static CartridgeSaveService.Grant authorizeSaveManager(ServerPlayer player,CartridgeEditBinding target) {
        if(player==null||player.getServer()==null||!player.getServer().isSameThread())return null;
        State state=STATES.get(player.getServer());Session session=state==null?null:state.sessions.get(player.getUUID());
        if(session==null||session.processing||session.upload!=null||!state.valid(player,session,target)
                ||session.processing||session.upload!=null||!PlayerContentAccess.canBrowse(player))return null;
        return new CartridgeSaveService.Grant(session.computer,session.target.hand()==0?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND,
                session.stack,session.target.cartridgeId(),FcCartridgeData.romSha(session.stack));
    }
    public static void computerRemoved(MinecraftServer server, CartridgeComputerBlockEntity computer) {
        if (!server.isSameThread()) return;
        State state = STATES.get(server);
        if (state == null) return;
        for (Session session : List.copyOf(state.sessions.values()))
            if (session.computer == computer) state.cancel(session.playerId, "电脑已拆除或卸载，卡带编辑已取消，原内容保留。");
    }
    private static CartridgeComputerBlockEntity loadedComputer(ServerPlayer player, BlockPos pos) {
        if (pos == null || player.getServer() == null || !player.getServer().isSameThread()
                || !player.serverLevel().hasChunkAt(pos)) return null;
        return player.serverLevel().getBlockState(pos).getBlock() instanceof CartridgeComputerBlock
                && player.serverLevel().getBlockEntity(pos) instanceof CartridgeComputerBlockEntity computer
                && !computer.isRemoved() ? computer : null;
    }
    private static boolean computerFacts(ServerPlayer player, CartridgeComputerBinding binding, CartridgeComputerBlockEntity expected) {
        BlockPos pos = new BlockPos(binding.x(), binding.y(), binding.z());
        CartridgeComputerBlockEntity actual = loadedComputer(player, pos);
        return binding.permits(actual == null ? null : actual.computerId(), player.serverLevel().dimension().location().toString(),
                actual == expected && expected.getLevel() == player.serverLevel(), actual != null,
                player.isAlive() && !player.isSpectator() && !player.hasDisconnected(), PlayerContentAccess.canBrowse(player),
                player.serverLevel().mayInteract(player, pos), player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5));
    }
    private static boolean computerPermitted(ServerPlayer player, InteractionHand hand, CartridgeComputerBinding binding,
                                             CartridgeComputerBlockEntity expected) {
        if (CHECKING_COMPUTER.get() || !computerFacts(player, binding, expected)) return false;
        BlockPos pos = new BlockPos(binding.x(), binding.y(), binding.z());
        CHECKING_COMPUTER.set(true);
        try {
            // Reconsult protection mods that honor the standard interaction event.
            // This does not promise compatibility with private claim APIs.
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), player.getDirection().getOpposite(), pos, false);
            var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit));
            return !event.isCanceled() && event.getUseBlock() != TriState.FALSE && event.getUseItem() != TriState.FALSE
                    && computerFacts(player, binding, expected);
        } catch (RuntimeException error) {
            cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("[PIQ FC] 卡带电脑交互许可检查失败，拒绝编辑", error);
            return false;
        } finally { CHECKING_COMPUTER.remove(); }
    }
    private static boolean uniqueCard(ServerPlayer player, ItemStack expected, UUID cartridgeId) {
        if (cartridgeId == null) return false;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ItemStack, Boolean>());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) seen.add(player.getInventory().getItem(slot));
        for (var slot : player.inventoryMenu.slots) seen.add(slot.getItem());
        seen.add(player.containerMenu.getCarried());
        int count = 0;
        for (ItemStack stack : seen) if (!stack.isEmpty() && cartridgeId.equals(FcCartridgeData.id(stack))) {
            if (stack != expected || stack.getCount() != 1) return false;
            count++;
        }
        return count == 1;
    }
    public static void cancelForAssembly(ServerPlayer player) {
        State state = STATES.get(player.getServer());
        if (state != null) state.cancel(player.getUUID(), "卡带已拆装，旧编辑会话失效。");
    }
    public static void handle(ServerPlayer player, Request request) {
        State state = state(player);
        if (state == null) return;
        if (request.operation() == CartridgeNetwork.DOWNLOAD_COVER) { state.download(player, request.hash()); return; }
        Session session = state.sessions.get(player.getUUID());
        if (session == null || !state.valid(player, session, request.target())) return;
        if (request.operation() == CartridgeNetwork.CANCEL) { state.cancel(player.getUUID(), ""); return; }
        int tick = state.server.getTickCount();
        session.lastTouched = tick;
        try {
            if (session.upload != null && !session.processing && session.upload.buffer.expired(System.nanoTime()))
                throw new IllegalStateException("上传已超时，请重新选择文件");
            if (request.operation() == CartridgeNetwork.CHUNK) {
                // Arrival ticks can coalesce during low TPS/latency. Ordered offsets and
                // the reserved total bound accepted work without rejecting legitimate bursts.
                if (session.upload == null || session.processing || !session.upload.hash.equals(request.hash()))
                    throw new IllegalArgumentException("没有匹配的卡带上传");
                state.requireUpload(player, session.upload.cover);
                session.upload.buffer.append(request.offset(), request.data());
                return;
            }
            if (!session.controlRate.allow(tick)) return;
            switch (request.operation()) {
                case CartridgeNetwork.REFRESH -> { state.send(player, session, CartridgeNetwork.OPEN, "", "游戏库已刷新", state.library.homeCatalog()); state.refreshCovers(player, session); }
                case CartridgeNetwork.WRITE -> state.write(player, session, request);
                case CartridgeNetwork.START_ROM, CartridgeNetwork.START_COVER -> state.start(player, session, request);
                case CartridgeNetwork.FINISH -> state.finish(player, session, request);
                case CartridgeNetwork.SET_PLAYERS -> state.setPlayers(player, session, request);
                case CartridgeNetwork.SET_SAVE_MODE -> state.setSaveMode(player, session, request);
                default -> { }
            }
        } catch (RuntimeException error) {
            state.abortUpload(session);
            state.send(player, session, CartridgeNetwork.STATUS, "", safeMessage(error), List.of());
        }
    }
    private static void tick(ServerTickEvent.Post event) {
        State state = STATES.get(event.getServer());
        if (state != null) state.tick();
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            State state = STATES.get(player.getServer());
            if (state != null) { state.cancel(player.getUUID(), ""); state.cancelDownload(player.getUUID());
                state.lastOpen.remove(player.getUUID()); state.lastDownload.remove(player.getUUID()); }
        }
    }
    private static void stopped(ServerStoppedEvent event) {
        State state = STATES.remove(event.getServer());
        if (state == null) return;
        state.closed = true;
        for (UUID player : List.copyOf(state.sessions.keySet())) state.cancel(player, "");
        for (UUID player : List.copyOf(state.downloads.keySet())) state.cancelDownload(player);
    }
    private static String safeMessage(Throwable error) {
        String message = error.getMessage() == null ? "卡带操作失败" : error.getMessage();
        message = message.replaceAll("\\p{Cntrl}", " ");
        return message.substring(0, Math.min(message.length(), 200));
    }
    private static State state(ServerPlayer player) {
        if (player == null || player.getServer() == null || !player.getServer().isSameThread()) return null;
        try { return STATES.computeIfAbsent(player.getServer(), State::new); }
        catch (java.io.UncheckedIOException error) {
            cn.piq.fcarcade.FcArcadeMod.LOGGER.error("[PIQ FC] 服务器卡带目录初始化失败；旧数据保留，拒绝使用空库", error);
            player.sendSystemMessage(Component.literal("FC 服务器卡带目录不可用；旧数据已保留，请管理员检查日志。"));
            return null;
        }
    }
    private static final class State {
        final MinecraftServer server;
        final ServerRomLibrary library;
        final CartridgeCoverRepository covers;
        final Map<UUID, Session> sessions = new HashMap<>();
        final Map<UUID, Download> downloads = new HashMap<>();
        final Map<UUID, Integer> lastOpen = new HashMap<>(), lastDownload = new HashMap<>();
        volatile boolean closed;
        State(MinecraftServer server) {
            this.server = server;
            this.library = ServerArcadeSessions.cartridgeLibrary(server);
            covers = new CartridgeCoverRepository(cn.piq.fcarcade.storage.FcStoragePaths.prepareUnchecked(
                    server.getServerDirectory(), cn.piq.fcarcade.storage.FcStoragePaths.Area.SHARED_COVERS));
        }
        boolean valid(ServerPlayer player, Session session, CartridgeEditBinding claimed) {
            if (closed || session.cancelled || sessions.get(player.getUUID()) != session) return false;
            if (!cardMatches(player, session, claimed)) return false;
            InteractionHand hand = session.target.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            return computerPermitted(player, hand, session.station, session.computer)
                    && !closed && !session.cancelled && sessions.get(player.getUUID()) == session
                    && cardMatches(player, session, claimed); // The public permission event may mutate the held item.
        }
        boolean cardMatches(ServerPlayer player, Session session, CartridgeEditBinding claimed) {
            InteractionHand hand = session.target.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack held = player.getItemInHand(hand);
            int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
            return player.containerMenu == player.inventoryMenu && FcCartridgeData.isCartridge(held) && held.getCount() == 1
                    && FcCartridgeData.supportsAssembly(held) && ItemStack.isSameItemSameComponents(held, session.expectedContents)
                    && uniqueCard(player, held, session.target.cartridgeId()) && session.target.permits(claimed,
                    FcCartridgeData.id(held), slot, held == session.stack, player.isAlive() && !player.isSpectator(), PlayerContentAccess.canBrowse(player));
        }
        void send(ServerPlayer player, Session session, int op, String hash, String message, List<RomCatalogEntry> catalog) {
            if (player.connection == null) return;
            session.capabilities = PlayerContentAccess.capabilities(player);
            CartridgeNetwork.reply(player, new Reply(op, session.target, hash, FcCartridgeData.romSha(session.stack),
                    FcCartridgeData.coverSha(session.stack), FcCartridgeData.title(session.stack), message, 0, 0, new byte[0], catalog, session.capabilities,
                    PlayerContentAccess.canUseServerCover(player) ? session.coverCatalog : List.of(),FcCartridgeData.saveMode(session.stack)));
        }
        void refreshCovers(ServerPlayer player, Session session) {
            if (session.coverCatalogLoading || !valid(player, session, session.target) || !PlayerContentAccess.canUseServerCover(player)) return;
            session.coverCatalogLoading = true;
            try { IO.execute(() -> {
                List<String> found;
                try { found = covers.list(); } catch (Exception failure) { found = List.of(); }
                List<String> result = found;
                server.execute(() -> {
                    session.coverCatalogLoading = false;
                    ServerPlayer current = server.getPlayerList().getPlayer(session.playerId);
                    if (current == null || !valid(current, session, session.target)) return;
                    session.coverCatalog = PlayerContentAccess.canUseServerCover(current) ? result : List.of();
                    send(current, session, CartridgeNetwork.STATUS, "", "封面目录已刷新", library.homeCatalog());
                });
            }); } catch (java.util.concurrent.RejectedExecutionException full) { session.coverCatalogLoading = false; }
        }
        void commit(ServerPlayer player, Session session, String rom, String name, String cover, String message, int requiredPermission) {
            if (!valid(player, session, session.target)) return;
            // The protection callback above may revoke upload permission. Check after it, at the actual write.
            if (requiredPermission != 0 && !PlayerContentPolicy.has(PlayerContentAccess.capabilities(player), requiredPermission)) {
                send(player, session, CartridgeNetwork.STATUS, "", "资源使用或上传权限已关闭，卡带原内容保留。", List.of()); return;
            }
            var selectedRom = rom.isEmpty() ? null : library.find(rom);
            if (!rom.isEmpty() && selectedRom == null) {
                send(player, session, CartridgeNetwork.STATUS, "", "ROM 已从服务器库移除，卡带原内容保留。", List.of()); return;
            }
            if (selectedRom != null && !cn.piq.fcarcade.rom.NesCompatibility.isSupported(selectedRom.header())) {
                send(player, session, CartridgeNetwork.STATUS, "", cn.piq.fcarcade.rom.NesCompatibility.unsupportedReason(selectedRom.header().mapper()), List.of()); return;
            }
            FcCartridgeData.write(session.stack, rom, name, cover);
            session.expectedContents = session.stack.copy();
            player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
            send(player, session, CartridgeNetwork.STATUS, "", message, library.homeCatalog());
        }
        void setPlayers(ServerPlayer player, Session session, Request request) {
            if (!player.hasPermissions(2)) throw new IllegalArgumentException("只有 OP 可以修改全局游戏人数设置");
            if (session.upload != null || session.processing) {
                send(player, session, CartridgeNetwork.STATUS, "", "请等待当前写入完成，再设置人数。", List.of()); return;
            }
            if (!request.cover().isEmpty() || !request.title().isEmpty() || !request.fileName().isEmpty()
                    || request.offset() != 0 || request.data().length != 0
                    || !request.hash().equals(FcCartridgeData.romSha(session.stack))
                    || !CartridgeComputerBinding.permitsPlayersSetting(request.total(), library.find(request.hash()) != null, false))
                throw new IllegalArgumentException("请选择服务器中的游戏，并将人数设置为 1 或 2");
            if (!valid(player, session, request.target())) return;
            if (!player.hasPermissions(2) || !request.hash().equals(FcCartridgeData.romSha(session.stack))
                    || !CartridgeComputerBinding.permitsPlayersSetting(request.total(), library.find(request.hash()) != null,
                    session.upload != null || session.processing)) return;
            library.setMaxPlayers(request.hash(), request.total());
            send(player, session, CartridgeNetwork.STATUS, "", "人数已设置；明确设置仍由同一 ROM 的卡带/街机共用，已开的游戏请重开生效。", library.homeCatalog());
        }
        void setSaveMode(ServerPlayer player,Session session,Request request){
            if(!PlayerContentAccess.canBrowse(player))return;
            if(session.upload!=null||session.processing){send(player,session,CartridgeNetwork.STATUS,"","请等待当前写入完成，再设置存档。",List.of());return;}
            if(!CartridgeComputerBinding.permitsSaveModeSetting(request.total(),request.hash(),FcCartridgeData.romSha(session.stack),library.find(request.hash())!=null,false))
                throw new IllegalArgumentException("请先将所选游戏写入当前卡带，再设置存档方式");
            if(!valid(player,session,request.target()))return;
            if(!PlayerContentAccess.canBrowse(player))return;
            // Permission callbacks may edit the card or library. Recheck the exact written ROM immediately before mutation.
            if(!CartridgeComputerBinding.permitsSaveModeSetting(request.total(),request.hash(),FcCartridgeData.romSha(session.stack),library.find(request.hash())!=null,session.upload!=null||session.processing))return;
            FcCartridgeData.setSaveMode(session.stack,RomSaveMode.fromId(request.total()));
            session.expectedContents=session.stack.copy();
            player.inventoryMenu.broadcastChanges();player.containerMenu.broadcastChanges();
            send(player,session,CartridgeNetwork.STATUS,"","存档设置已保存，下次开机生效。",library.homeCatalog());
        }
        void write(ServerPlayer player, Session session, Request request) {
            if (session.upload != null || session.processing) throw new IllegalStateException("请等待当前传输完成或关闭界面取消");
            if (!request.hash().isEmpty() && library.find(request.hash()) == null) throw new IllegalArgumentException("服务器尚未拥有此 ROM");
            int required = !request.hash().isEmpty() && !request.hash().equals(FcCartridgeData.romSha(session.stack)) ? PlayerContentPolicy.SERVER_ROM_USE : 0;
            boolean newCover = !request.cover().isEmpty() && !request.cover().equals(FcCartridgeData.coverSha(session.stack))
                    && !request.cover().equals(session.originalCover);
            if (newCover) {
                required |= PlayerContentPolicy.SERVER_COVER_USE;
                if (!session.coverCatalog.contains(request.cover())) throw new IllegalArgumentException("请刷新并选择服务器封面目录中的图片");
            }
            final int requiredPermission = required;
            if (required != 0 && !PlayerContentPolicy.has(PlayerContentAccess.capabilities(player), required))
                throw new IllegalArgumentException("管理员未开放所选服务器资源的使用权限");
            if (request.cover().isEmpty() || request.cover().equals(FcCartridgeData.coverSha(session.stack))) {
                commit(player, session, request.hash(), request.title(), request.cover(), "卡带已写入", requiredPermission); return;
            }
            session.processing = true;
            try {
                IO.execute(() -> {
                    if (closed || session.cancelled) return;
                    String error = null;
                    try { covers.read(request.cover()); } catch (Exception failure) { error = safeMessage(failure); }
                    String failure = error;
                    if (closed || session.cancelled) return;
                    server.execute(() -> {
                        session.processing = false;
                        ServerPlayer current = server.getPlayerList().getPlayer(session.playerId);
                        if (current == null || !valid(current, session, request.target())) return;
                        if (failure != null) send(current, session, CartridgeNetwork.STATUS, "", failure, List.of());
                        else commit(current, session, request.hash(), request.title(), request.cover(), "卡带封面已应用", requiredPermission);
                    });
                });
            } catch (RuntimeException error) { session.processing = false; throw error; }
        }
        void start(ServerPlayer player, Session session, Request request) {
            if (session.upload != null || session.processing) throw new IllegalStateException("已有卡带传输在进行");
            boolean cover = request.operation() == CartridgeNetwork.START_COVER;
            requireUpload(player, cover);
            if (!CartridgeLimits.validHash(request.hash()) || request.total() <= 0
                    || request.total() > (cover ? CartridgeLimits.MAX_COVER_BYTES : RomRepository.MAX_ROM_BYTES))
                throw new IllegalArgumentException("卡带上传长度或哈希无效");
            if (!cover && library.find(request.hash()) != null) {
                commit(player, session, request.hash(), request.title(), FcCartridgeData.coverSha(session.stack), "服务器已有此 ROM，卡带已写入", PlayerContentPolicy.ROM_UPLOAD); return;
            }
            UUID reservation = UUID.randomUUID();
            if (!BUDGET.reserve(reservation, request.total())) throw new IllegalStateException("全局传输繁忙（最多 4 笔 / 64 MiB）");
            try {
                session.upload = new Upload(reservation, request.hash(), request.fileName(), request.title(), cover,
                        new CartridgeTransfer(request.total(), System.nanoTime()));
            } catch (RuntimeException | OutOfMemoryError error) { BUDGET.release(reservation); throw error; }
            send(player, session, CartridgeNetwork.UPLOAD_READY, request.hash(), "可以开始分块上传", List.of());
        }
        void finish(ServerPlayer player, Session session, Request request) {
            Upload upload = session.upload;
            if (upload == null || session.processing || !upload.hash.equals(request.hash())) throw new IllegalArgumentException("没有匹配的上传");
            requireUpload(player, upload.cover);
            byte[] bytes = upload.buffer.finish();
            session.processing = true;
            try {
                IO.execute(() -> {
                    String error = null;
                    try {
                        if (session.cancelled || closed) return;
                        if (upload.cover) covers.store(upload.hash, bytes);
                        else library.store(upload.fileName, upload.hash, bytes); // Existing iNES/mapper/SHA validator.
                    } catch (Exception failure) { error = safeMessage(failure); }
                    finally { BUDGET.release(upload.reservation); }
                    String failure = error;
                    if (closed || session.cancelled) return;
                    server.execute(() -> {
                        session.processing = false; session.upload = null;
                        ServerPlayer current = server.getPlayerList().getPlayer(session.playerId);
                        if (current == null || !valid(current, session, request.target())) return;
                        if (upload.revoked || !uploadAllowed(current, upload.cover)) {
                            send(current, session, CartridgeNetwork.STATUS, "", "上传权限已关闭；已接收文件可保留在服务器库，未写入卡带。", List.of()); return;
                        }
                        if (failure != null) send(current, session, CartridgeNetwork.STATUS, "", failure, List.of());
                        else if (upload.cover) commit(current, session, FcCartridgeData.romSha(session.stack),
                                FcCartridgeData.title(session.stack), upload.hash, "封面已上传并写入卡带", PlayerContentPolicy.COVER_UPLOAD);
                        else commit(current, session, upload.hash, upload.title, FcCartridgeData.coverSha(session.stack), "ROM 已上传并写入卡带", PlayerContentPolicy.ROM_UPLOAD);
                    });
                });
            } catch (RuntimeException error) {
                session.processing = false; abortUpload(session); throw error;
            }
        }
        void abortUpload(Session session) {
            if (session.upload == null || session.processing) return;
            BUDGET.release(session.upload.reservation); session.upload = null;
        }
        boolean uploadAllowed(ServerPlayer player, boolean cover) {
            return cover ? PlayerContentAccess.canUploadCover(player) : PlayerContentAccess.canUploadRom(player);
        }
        void requireUpload(ServerPlayer player, boolean cover) {
            if (!uploadAllowed(player, cover)) throw new IllegalArgumentException(cover ? "普通玩家封面上传未开放或当前玩家未获授权" : "普通玩家 ROM 上传未开放或当前玩家未获授权");
        }
        void cancel(UUID playerId, String message) {
            Session session = sessions.remove(playerId);
            if (session == null) return;
            session.cancelled = true;
            abortUpload(session);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && !message.isEmpty()) send(player, session, CartridgeNetwork.CLOSED, "", message, List.of());
        }
        void download(ServerPlayer player, String hash) {
            if (!CartridgeLimits.validHash(hash) || downloads.containsKey(player.getUUID())) return;
            int now = server.getTickCount();
            if (now - lastDownload.getOrDefault(player.getUUID(), now - 40) < 40) {
                downloadReply(player, new Download(hash), 0, 0, new byte[0], "请稍后重试封面下载"); return;
            }
            lastDownload.put(player.getUUID(), now);
            Download download = new Download(hash);
            if (!BUDGET.reserve(download.reservation, CartridgeLimits.MAX_COVER_BYTES)) { downloadReply(player, download, 0, 0, new byte[0], "服务器封面传输繁忙"); return; }
            downloads.put(player.getUUID(), download);
            try {
                IO.execute(() -> {
                    byte[] result = null; String error = null;
                    try { if (!closed) result = covers.read(hash); } catch (Exception failure) { error = safeMessage(failure); }
                    if (closed) { BUDGET.release(download.reservation); return; }
                    byte[] bytes = result; String failure = error;
                    server.execute(() -> {
                        ServerPlayer current = server.getPlayerList().getPlayer(player.getUUID());
                        if (current == null || closed || downloads.get(player.getUUID()) != download) { BUDGET.release(download.reservation); return; }
                        if (bytes == null) { cancelDownload(player.getUUID()); downloadReply(current, download, 0, 0, new byte[0], failure == null ? "封面不可用" : failure); }
                        else { download.bytes = bytes; downloadReply(current, download, bytes.length, 0, new byte[0], ""); }
                    });
                });
            } catch (RuntimeException error) { cancelDownload(player.getUUID()); downloadReply(player, download, 0, 0, new byte[0], "服务器封面队列已满"); }
        }
        void downloadReply(ServerPlayer player, Download download, int total, int offset, byte[] bytes, String message) {
            CartridgeNetwork.reply(player, new Reply(bytes.length == 0 ? CartridgeNetwork.COVER_START : CartridgeNetwork.COVER_CHUNK,
                    CartridgeNetwork.NO_TARGET, download.hash, "", "", "", message, total, offset, bytes, List.of(), PlayerContentAccess.capabilities(player)));
        }
        void cancelDownload(UUID player) { Download download = downloads.remove(player); if (download != null) BUDGET.release(download.reservation); }
        void tick() {
            int tick = server.getTickCount(); long now = System.nanoTime();
            for (Session session : List.copyOf(sessions.values())) {
                ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
                if (player == null || !valid(player, session, session.target) || tick - session.lastTouched > 20 * 300) {
                    cancel(session.playerId, "卡带编辑已取消：电脑、距离、权限或手中卡带发生变化，原内容保留。"); continue;
                }
                if (session.upload != null && !session.upload.revoked && !uploadAllowed(player, session.upload.cover)) {
                    session.upload.revoked = true; // Sticky: toggling back on must not resurrect this upload.
                    abortUpload(session); // IO-owned buffers remain reserved until the worker's finally block.
                    send(player, session, CartridgeNetwork.STATUS, "", "上传权限已关闭，本次上传不会写入卡带；原内容保留。", List.of());
                }
                if (session.upload != null && !session.processing && session.upload.buffer.expired(now)) {
                    abortUpload(session); send(player, session, CartridgeNetwork.STATUS, "", "上传超时，请重新选择文件", List.of());
                }
                if (session.capabilities != PlayerContentAccess.capabilities(player)) {
                    send(player, session, CartridgeNetwork.OPEN, "", "服务器内容权限已更新", library.homeCatalog());
                    refreshCovers(player, session);
                }
            }
            for (UUID playerId : List.copyOf(downloads.keySet())) {
                Download download = downloads.get(playerId); ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || now - download.started > CartridgeLimits.TRANSFER_TIMEOUT_NANOS) { cancelDownload(playerId); continue; }
                if (download.bytes == null) continue;
                for (int part = 0; part < CartridgeLimits.CHUNKS_PER_TICK && download.offset < download.bytes.length; part++) {
                    int end = Math.min(download.offset + CartridgeLimits.CHUNK_BYTES, download.bytes.length);
                    downloadReply(player, download, download.bytes.length, download.offset, Arrays.copyOfRange(download.bytes, download.offset, end), "");
                    download.offset = end;
                }
                if (download.offset == download.bytes.length) {
                    cancelDownload(playerId);
                    lastDownload.remove(playerId); // A successful sequential cache fill must not inherit failure cooldown.
                }
            }
        }
    }
    private static final class Session {
        final UUID playerId; final CartridgeEditBinding target; final ItemStack stack;
        final CartridgeComputerBinding station; final CartridgeComputerBlockEntity computer;
        final String originalCover;
        List<String> coverCatalog = List.of(); boolean coverCatalogLoading;
        ItemStack expectedContents;
        final Rate controlRate = new Rate(10, 1);
        int lastTouched, capabilities = -1; Upload upload; boolean processing; volatile boolean cancelled;
        Session(UUID playerId, CartridgeEditBinding target, ItemStack stack, CartridgeComputerBinding station,
                CartridgeComputerBlockEntity computer, int tick) {
            this.playerId = playerId; this.target = target; this.stack = stack; this.station = station;
            this.computer = computer; this.expectedContents = stack.copy(); this.lastTouched = tick;
            this.originalCover = FcCartridgeData.coverSha(stack);
        }
    }
    private static final class Upload {
        final UUID reservation; final String hash, fileName, title; final boolean cover; final CartridgeTransfer buffer;
        boolean revoked;
        Upload(UUID reservation, String hash, String fileName, String title, boolean cover, CartridgeTransfer buffer) {
            this.reservation = reservation; this.hash = hash; this.fileName = fileName; this.title = title;
            this.cover = cover; this.buffer = buffer;
        }
    }
    private static final class Download {
        final UUID reservation = UUID.randomUUID(); final String hash; final long started = System.nanoTime();
        byte[] bytes; int offset;
        Download(String hash) { this.hash = hash; }
    }
    private static final class Rate {
        final int capacity, refill; int tokens, lastTick = Integer.MIN_VALUE;
        Rate(int capacity, int refill) { this.capacity = capacity; this.refill = refill; tokens = capacity; }
        boolean allow(int tick) {
            if (lastTick != Integer.MIN_VALUE && tick > lastTick) tokens = (int) Math.min(capacity, tokens + (long) (tick - lastTick) * refill);
            lastTick = tick;
            if (tokens == 0) return false;
            tokens--; return true;
        }
    }
}
