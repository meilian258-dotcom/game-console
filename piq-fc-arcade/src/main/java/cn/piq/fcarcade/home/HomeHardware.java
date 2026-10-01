package cn.piq.fcarcade.home;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

/** Loaded-endpoint operations only. Every mutation runs on the server game thread. */
public final class HomeHardware {
    private static final String SELECTION = "PiqHomeAvSelection";
    private static final ThreadLocal<Boolean> CHECKING_ANCHOR_INTERACTION = ThreadLocal.withInitial(() -> false);
    private static final cn.piq.fcarcade.server.InteractionTransaction AV_TRANSACTIONS = new cn.piq.fcarcade.server.InteractionTransaction();
    private HomeHardware() {}

    public static void register() {
        HomeControllerService.register();
        HomeSystems.installLifecycle();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                HomeApplianceService.clearEmptyPowerFor(event.getServer()));
        // Lifecycle is provided by the two block tickers, onRemove and BE
        // onChunkUnloaded hooks. No world-wide scanner or extra clock is needed.
    }

    public static HomeConsoleBlockEntity connectedConsole(Level level, BlockPos tvPos) {
        var endpoint = connectedEndpoint(level, tvPos);
        return endpoint instanceof HomeConsoleBlockEntity console ? console : null;
    }

    /**
     * Read-only, loaded-hardware check for client-local playback and other callers.
     * Includes both peer identities, AV range and complete structures; it does not
     * authorize a player, controller, ROM, power change or public/private session.
     */
    public static boolean connected(Level level, BlockEntity console, HomeTvBlockEntity television) {
        if (level == null || console == null || television == null
                || console.getLevel() != level || television.getLevel() != level
                || console.isRemoved() || television.isRemoved()) return false;
        return loadedEndpoint(level, television.getBlockPos()) == television
                && connectedEndpoint(level, television.getBlockPos()) == console;
    }

    /** Package-only generalization: old public FC queries retain their NES-only meaning. */
    static HomeEndpointBlockEntity connectedEndpoint(Level level, BlockPos tvPos) {
        if (tvPos == null) return null;
        BlockPos resolved = HomeTvStructure.resolveAnchor(level, tvPos);
        if (resolved == null || !HomeTvStructure.complete(level, resolved)) return null;
        tvPos = resolved;
        if (!(loadedEndpoint(level, tvPos) instanceof HomeTvBlockEntity tv)
                || tv.consolePos() == null || tv.linkId() == null) return null;
        HomeEndpointBlockEntity console = loadedEndpoint(level, tv.consolePos());
        if (console == null || console.getLevel() != level || !mutual(console, tv) || !consoleComplete(console)
                || !sameSupportedSpace(console,tv) || !console.linkSpaceValid() || !tv.linkSpaceValid()) return null;
        if (level instanceof ServerLevel serverLevel) {
            var link = HomeLinkData.get(serverLevel).ledger.get(tv.linkId());
            if (link == null || link.closed() || !link.console().equals(console.endpoint())
                    || !link.tv().equals(tv.endpoint())) return null;
        }
        return console;
    }

    public static String selectedRom(Level level, BlockPos tvPos) {
        HomeConsoleBlockEntity console = connectedConsole(level, tvPos);
        return console == null ? "" : console.romSha();
    }

    public static boolean validPlayback(Level level, BlockPos tvPos) {
        return selectedRom(level, tvPos).matches("[0-9a-f]{64}");
    }

    private static boolean mutual(HomeEndpointBlockEntity console, HomeTvBlockEntity tv) {
        return console.kind() == HomeLinkLedger.Kind.CONSOLE && console.linkId() != null && console.linkId().equals(tv.linkId())
                && console.hardwareId().equals(tv.peerId()) && tv.hardwareId().equals(console.peerId())
                && console.getBlockPos().equals(tv.consolePos()) && tv.getBlockPos().equals(console.peerPos())
                && HomeLinkLedger.inRange(console.endpoint(), tv.endpoint());
    }

    private static boolean consoleComplete(HomeEndpointBlockEntity console) {
        if (console instanceof HomeConsoleBlockEntity fc) return SuborStructure.complete(fc.getLevel(), fc.getBlockPos());
        return console instanceof ExternalHomeConsoleBlockEntity external && HomeSystems.complete(external);
    }

    private static boolean sameSupportedSpace(HomeEndpointBlockEntity console, HomeEndpointBlockEntity tv) {
        return cn.piq.fcarcade.compat.HomeShipSpace.canLink(
                console instanceof HomeConsoleBlockEntity && !(console.getBlockState().getBlock() instanceof SuborConsoleBlock),
                cn.piq.fcarcade.compat.HomeShipSpace.at(console.getLevel(),console.getBlockPos()),
                cn.piq.fcarcade.compat.HomeShipSpace.at(tv.getLevel(),tv.getBlockPos()));
    }

    static HomeEndpointBlockEntity loadedEndpoint(Level level, BlockPos pos) {
        if (pos == null || !level.hasChunkAt(pos)) return null;
        if (level.getBlockEntity(pos) instanceof HomeTvPartBlockEntity
                || level.getBlockEntity(pos) instanceof WideLcdTvPartBlockEntity
                || level.getBlockEntity(pos) instanceof LargeLcdTvPartBlockEntity
                || level.getBlockEntity(pos) instanceof PanelTvPartBlockEntity) {
            pos = HomeTvStructure.resolveAnchor(level, pos);
            if (pos == null) return null;
        }
        if (level.getBlockEntity(pos) instanceof SuborPartBlockEntity) {
            pos = SuborStructure.resolveAnchor(level, pos);
            if (pos == null) return null;
        }
        return level.getBlockEntity(pos) instanceof HomeEndpointBlockEntity endpoint && !endpoint.isRemoved()
                ? endpoint : null;
    }

    public static boolean mayUse(ServerPlayer player, BlockPos pos) {
        return player.getServer().isSameThread() && player.serverLevel().hasChunkAt(pos)
                && player.serverLevel().mayInteract(player, pos)
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    private static InteractionResult message(ServerPlayer player, String key) {
        HomeFeedback.show(player, "home_" + key);
        return InteractionResult.CONSUME;
    }

    public static InteractionResult insertCartridge(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        return insertCartridge(player, pos, hand, new BlockHitResult(Vec3.atCenterOf(pos), player.getDirection(), pos, false));
    }

    public static InteractionResult insertCartridge(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult hit) {
        if (!mayUse(player, pos)) return message(player, "denied");
        if (!(loadedEndpoint(player.serverLevel(), pos) instanceof HomeConsoleBlockEntity console))
            return player.serverLevel().getBlockState(pos).getBlock() instanceof SuborPartBlock
                    ? message(player, "subor_incomplete") : InteractionResult.PASS;
        if (!allowAnchorInteraction(player, pos, console, hand, hit)) return message(player, "denied");
        if (!SuborStructure.complete(player.serverLevel(), console.getBlockPos())) return message(player, "subor_incomplete");
        // Shift belongs to the cartridge editor, never to an insertion transaction.
        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (!FcCartridgeData.isPlayable(held) || held.getCount() != 1) return InteractionResult.PASS;
        if (console.hasCartridge()) return message(player, "card_present");
        if (!FcCartridgeData.romSha(held).matches("[0-9a-f]{64}")) return message(player, "blank_card");
        try {
            FcCartridgeData.ensureIdentity(held);
            if (FcCartridgeData.isBoard(held)) FcCartridgeData.board(held);
        } catch (IllegalArgumentException invalid) { return message(player, "blank_card"); }
        if (!console.insert(held)) return message(player, "card_present");
        held.shrink(1);
        HomeApplianceService.cartridgeChanged(player.serverLevel(),console.getBlockPos());
        HomeInteractionSounds.play(player.serverLevel(),console.getBlockPos(),HomeInteractionSounds.Action.CARTRIDGE_INSERT);
        // Commit the physical item transaction first. A ROM/start failure must
        // never put a second copy back into the player's hand.
        if (console.tvPos() == null || connectedConsole(player.serverLevel(), console.tvPos()) != console)
            return message(player, "card_inserted_unlinked");
        message(player, "card_inserted");
        // Inserting media never powers the console or borrows a controller.
        return InteractionResult.CONSUME;
    }

    public static InteractionResult interactConsole(ServerPlayer player, BlockPos pos) {
        return interactConsole(player, pos, new BlockHitResult(Vec3.atCenterOf(pos), player.getDirection(), pos, false));
    }

    public static InteractionResult interactConsole(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        var unplug = ZapperStandService.tryDisconnect(player,pos,hit);
        if (unplug != InteractionResult.PASS) return unplug;
        if (!mayUse(player, pos)) return message(player, "denied");
        if (!(loadedEndpoint(player.serverLevel(), pos) instanceof HomeConsoleBlockEntity console))
            return player.serverLevel().getBlockState(pos).getBlock() instanceof SuborPartBlock
                    ? message(player, "subor_incomplete") : InteractionResult.PASS;
        if (!allowAnchorInteraction(player, pos, console, InteractionHand.MAIN_HAND, hit)) return message(player, "denied");
        if (!SuborStructure.complete(player.serverLevel(), console.getBlockPos())) return message(player, "subor_incomplete");
        if (player.isShiftKeyDown()) {
            var button = HomeApplianceService.tryButton(player,pos,InteractionHand.MAIN_HAND,hit);
            if (button != InteractionResult.PASS) return button;
            if (!console.hasCartridge()) return message(player, "no_card");
            stopEndpoint(console);
            giveOrDrop(player, console.takeCartridge());
            HomeInteractionSounds.play(player.serverLevel(),console.getBlockPos(),HomeInteractionSounds.Action.CARTRIDGE_EJECT);
            // Stop requests a best-effort save; this message does not claim it has finished.
            return message(player, "card_ejected");
        }
        return HomeApplianceService.interactConsole(player,pos,hit);
    }

    public static InteractionResult interactTv(ServerPlayer player, BlockPos pos) {
        return interactTv(player, pos, new BlockHitResult(Vec3.atCenterOf(pos), player.getDirection(), pos, false));
    }

    public static InteractionResult interactTv(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        if (!mayUse(player, pos)) return message(player, "denied");
        HomeEndpointBlockEntity endpoint = loadedEndpoint(player.serverLevel(), pos);
        if (!(endpoint instanceof HomeTvBlockEntity tv)) return message(player, "tv_incomplete");
        if (!allowAnchorInteraction(player, pos, endpoint, InteractionHand.MAIN_HAND, hit)) return message(player, "denied");
        pos = tv.getBlockPos();
        if (!player.serverLevel().mayInteract(player, pos)) return message(player, "denied");
        if (!tv.structureInstalled()) return message(player, "tv_rebuild");
        if (!HomeTvStructure.complete(player.serverLevel(), pos)) return message(player, "tv_incomplete");
        return HomeApplianceService.interactTv(player,hit.getBlockPos(),hit);
    }

    public static InteractionResult useCable(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        return useCable(player, pos, hand, new BlockHitResult(Vec3.atCenterOf(pos), player.getDirection(), pos, false));
    }

    public static InteractionResult useCable(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult hit) {
        if (AV_TRANSACTIONS.active() || !currentCablePlayer(player) || !mayUse(player, pos)) return message(player, "denied");
        ServerLevel level = player.serverLevel();
        var source = player.connection.getConnection();
        ItemStack wire = player.getItemInHand(hand);
        if (!wire.is(ModItems.AV_CABLE.get()) || wire.isEmpty()) return InteractionResult.PASS;
        var endpoint = loadedEndpoint(level, pos);
        if (endpoint == null) return useCablePermitted(player, pos, hand, hit);
        ItemStack originalWire = wire.copy();
        var clickedState = level.getBlockState(pos);
        var clickedEntity = level.getBlockEntity(pos);
        var endpointState = endpoint.getBlockState();
        var endpointIdentity = endpoint.endpoint();
        var originalLink = endpoint.linkId();
        CompoundTag selection = wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(SELECTION);
        HomeEndpointBlockEntity other = null;
        if (player.isShiftKeyDown() && originalLink != null) {
            other = loadedEndpoint(level, endpoint.peerPos());
            // Manual edits require current permissions on both live ends; lifecycle cleanup is separate.
            if (other == null) return message(player, "denied");
        } else if (!player.isShiftKeyDown() && selection.hasUUID("HardwareId") && selection.contains("Pos")
                && selection.getString("Dimension").equals(level.dimension().location().toString())) {
            other = loadedEndpoint(level, BlockPos.of(selection.getLong("Pos")));
        }
        final HomeEndpointBlockEntity first = other;
        var firstIdentity = first == null ? null : first.endpoint();
        var firstState = first == null ? null : first.getBlockState();
        var firstLink = first == null ? null : first.linkId();
        boolean shifting = player.isShiftKeyDown();
        java.util.function.BooleanSupplier facts = () -> currentCablePlayer(player)
                && player.connection.getConnection() == source && player.serverLevel() == level
                && player.isShiftKeyDown() == shifting && player.getItemInHand(hand) == wire && ItemStack.matches(wire, originalWire)
                && mayUse(player, pos) && level.getBlockState(pos) == clickedState && level.getBlockEntity(pos) == clickedEntity
                && sameCableEndpoint(level, endpoint, endpointIdentity, endpointState, originalLink)
                && (first == null || sameCableEndpoint(level, first, firstIdentity, firstState, firstLink)
                    && level.mayInteract(player, first.getBlockPos())
                    && player.distanceToSqr(Vec3.atCenterOf(first.getBlockPos())) <= 256.0);
        InteractionResult[] result = {InteractionResult.CONSUME};
        boolean accepted = AV_TRANSACTIONS.run(facts,
                () -> cablePermission(player, pos, hand, hit) && facts.getAsBoolean()
                    && (pos.equals(endpoint.getBlockPos()) || cablePermission(player, endpoint.getBlockPos(), hand, hit))
                    && facts.getAsBoolean() && (first == null || first == endpoint || cablePermission(player, first.getBlockPos(), hand, hit)),
                () -> result[0] = useCablePermitted(player, pos, hand, hit));
        return accepted ? result[0] : message(player, "denied");
    }

    private static boolean currentCablePlayer(ServerPlayer player) {
        return player != null && player.getServer() != null && player.getServer().isSameThread()
                && player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && player.connection.getConnection().isConnected()
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean sameCableEndpoint(ServerLevel level, HomeEndpointBlockEntity endpoint, HomeLinkLedger.Endpoint identity,
            net.minecraft.world.level.block.state.BlockState state, UUID link) {
        return !endpoint.isRemoved() && endpoint.getLevel() == level && level.hasChunkAt(endpoint.getBlockPos())
                && level.getBlockEntity(endpoint.getBlockPos()) == endpoint && endpoint.getBlockState() == state
                && level.getBlockState(endpoint.getBlockPos()) == state
                && identity.equals(endpoint.endpoint()) && java.util.Objects.equals(link, endpoint.linkId());
    }

    private static boolean cablePermission(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult original) {
        try {
            var hit = new BlockHitResult(original.getLocation(), original.getDirection(), pos, original.isInside());
            var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit));
            return !event.isCanceled() && event.getUseBlock() != TriState.FALSE && event.getUseItem() != TriState.FALSE;
        } catch (RuntimeException | LinkageError failure) {
            FcArcadeMod.LOGGER.warn("[PIQ FC] AV endpoint permission check failed closed", failure);
            return false;
        }
    }

    private static InteractionResult useCablePermitted(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult hit) {
        if (!mayUse(player, pos)) return message(player, "denied");
        ServerLevel level = player.serverLevel();
        HomeEndpointBlockEntity endpoint = loadedEndpoint(level, pos);
        if (endpoint == null) return (level.getBlockState(pos).getBlock() instanceof HomeTvPartBlock
                || level.getBlockState(pos).getBlock() instanceof WideLcdTvPartBlock
                || level.getBlockState(pos).getBlock() instanceof LargeLcdTvPartBlock
                || level.getBlockState(pos).getBlock() instanceof PanelTvPartBlock)
                ? message(player, "tv_incomplete") : level.getBlockState(pos).getBlock() instanceof SuborPartBlock
                ? message(player, "subor_incomplete") : InteractionResult.PASS;
        pos = endpoint.getBlockPos();
        if (!level.mayInteract(player, pos)) return message(player, "denied");
        ItemStack wire = player.getItemInHand(hand);
        if (!wire.is(ModItems.AV_CABLE.get()) || wire.isEmpty()) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            clearSelection(wire);
            if (endpoint.linkId() == null) return message(player, "wire_selection_cleared");
            disconnect(endpoint, player);
            return message(player, "wire_disconnected");
        }
        if (endpoint instanceof HomeTvBlockEntity tv) {
            if (!tv.structureInstalled()) return message(player, "tv_rebuild");
            if (!HomeTvStructure.complete(level, pos)) return message(player, "tv_incomplete");
        }
        if (endpoint.kind() == HomeLinkLedger.Kind.CONSOLE && !consoleComplete(endpoint))
            return message(player, "subor_incomplete");
        if (endpoint.linkId() != null) return message(player, "wire_occupied");
        CompoundTag selection = wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getCompound(SELECTION);
        if (!selection.hasUUID("HardwareId") || !selection.contains("Pos")) {
            CompoundTag selected = new CompoundTag();
            selected.putUUID("HardwareId", endpoint.hardwareId());
            selected.putString("Dimension", level.dimension().location().toString());
            selected.putLong("Pos", pos.asLong());
            CustomData.update(DataComponents.CUSTOM_DATA, wire, tag -> tag.put(SELECTION, selected));
            return message(player, "wire_selected");
        }
        if (!selection.getString("Dimension").equals(level.dimension().location().toString()))
            return message(player, "wire_dimension");
        BlockPos firstPos = BlockPos.of(selection.getLong("Pos"));
        // The first endpoint was selected by a normal block click. At the
        // second click the player may be beyond its 8-block radius while the
        // endpoints themselves are still <=8 blocks apart (e.g. standing outside TV).
        if (!level.hasChunkAt(firstPos) || !level.mayInteract(player, firstPos)
                || player.distanceToSqr(firstPos.getX() + 0.5, firstPos.getY() + 0.5,
                firstPos.getZ() + 0.5) > 256.0) return message(player, "denied");
        HomeEndpointBlockEntity first = loadedEndpoint(level, firstPos);
        if (first == null || !first.hardwareId().equals(selection.getUUID("HardwareId"))) {
            clearSelection(wire);
            return message(player, "wire_first_missing");
        }
        if (first instanceof HomeTvBlockEntity tv) {
            if (!tv.structureInstalled()) return message(player, "tv_rebuild");
            if (!HomeTvStructure.complete(level, firstPos)) return message(player, "tv_incomplete");
        }
        if (first.kind() == HomeLinkLedger.Kind.CONSOLE && !consoleComplete(first))
            return message(player, "subor_incomplete");
        if (first.kind() == endpoint.kind()) return message(player, "wire_same_type");
        if (first.linkId() != null) return message(player, "wire_occupied");
        HomeEndpointBlockEntity console = first.kind() == HomeLinkLedger.Kind.CONSOLE ? first : endpoint;
        HomeEndpointBlockEntity tv = first.kind() == HomeLinkLedger.Kind.TV ? first : endpoint;
        if (!sameSupportedSpace(console,tv)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "航空适配首版：先组装载具，再放置 FC 与电视并接线；不能跨船或接地面，组装/拆解前请拆下设备。"),true);
            return InteractionResult.CONSUME;
        }
        if (!HomeLinkLedger.inRange(console.endpoint(), tv.endpoint())) return message(player, "wire_range");
        var consoleSpace = cn.piq.fcarcade.compat.HomeShipSpace.at(level,console.getBlockPos());
        if (!cn.piq.fcarcade.compat.HomeShipSpace.same(consoleSpace,
                cn.piq.fcarcade.compat.HomeShipSpace.at(level,tv.getBlockPos()))) return message(player,"denied");
        HomeLinkData data = HomeLinkData.get(level);
        var link = data.ledger.connect(console.endpoint(), tv.endpoint());
        if (link == null) return message(player, "wire_occupied");
        // No scheduling/await between validation, both endpoint writes and debit.
        console.attach(link,consoleSpace.id()); tv.attach(link,consoleSpace.id());
        clearSelection(wire);
        wire.shrink(1); // Paid links alone can later return one wire, including creative use.
        data.setDirty();
        HomeInteractionSounds.play(level,pos,HomeInteractionSounds.Action.CABLE_CONNECT);
        message(player, "wire_connected");
        // A cable is hardware, not an implicit power or input action.
        if (console instanceof ExternalHomeConsoleBlockEntity external) HomeSystems.linked(player, external);
        HomeApplianceService.refresh(level,tv.getBlockPos());
        return InteractionResult.CONSUME;
    }

    static boolean allowAnchorInteraction(ServerPlayer player, BlockPos clicked,
                                                   HomeEndpointBlockEntity endpoint, InteractionHand hand,
                                                   BlockHitResult hit) {
        var anchor = endpoint.getBlockPos();
        if (clicked.equals(anchor)) return true; // Original vanilla event already covered this position.
        var level = player.serverLevel();
        if (!level.hasChunkAt(anchor) || !level.hasChunkAt(clicked)
                || !level.mayInteract(player, anchor) || CHECKING_ANCHOR_INTERACTION.get()) return false;
        var clickedEntity = level.getBlockEntity(clicked);
        CHECKING_ANCHOR_INTERACTION.set(true);
        try {
            // Keep the true world hit point/face on the large model, but identify
            // the anchor as the target whose public protection event is consulted.
            var anchorHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), anchor, hit.isInside());
            var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, anchor, anchorHit));
            return !event.isCanceled() && event.getUseBlock() != TriState.FALSE && event.getUseItem() != TriState.FALSE
                    && level.hasChunkAt(anchor) && level.hasChunkAt(clicked)
                    && level.getBlockEntity(anchor) == endpoint && level.getBlockEntity(clicked) == clickedEntity;
        } finally { CHECKING_ANCHOR_INTERACTION.remove(); }
    }

    private static void clearSelection(ItemStack wire) {
        CustomData.update(DataComponents.CUSTOM_DATA, wire, tag -> tag.remove(SELECTION));
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    private static void stopEndpoint(HomeEndpointBlockEntity endpoint) {
        stopEndpoint(endpoint, HomeSystems.StopReason.INVALID_CONNECTION);
    }

    private static void stopEndpoint(HomeEndpointBlockEntity endpoint, HomeSystems.StopReason reason) {
        if (!(endpoint.getLevel() instanceof ServerLevel level)) return;
        HomeApplianceService.clearEmptyPower(endpoint);
        if (endpoint instanceof ExternalHomeConsoleBlockEntity external) {
            // The original BE is still available during unload even if its peer is not.
            HomeSystems.stopped(external, reason);
            return;
        }
        BlockPos tvPos;
        if (endpoint instanceof HomeTvBlockEntity television) {
            tvPos = endpoint.getBlockPos();
            // Do not stop a replacement television at the same coordinates.
            if (level.hasChunkAt(tvPos) && level.getBlockEntity(tvPos) != endpoint) return;
            if (loadedEndpoint(level, television.consolePos()) instanceof ExternalHomeConsoleBlockEntity external
                    && mutual(external, television)) {
                HomeSystems.stopped(external, reason);
                return;
            }
        } else {
            tvPos = endpoint.peerPos();
            if (!(loadedEndpoint(level, tvPos) instanceof HomeTvBlockEntity tv)
                    || !mutual(endpoint, tv)) return;
        }
        try {
            ServerArcadeSessions.stopHomeConsole(level.getServer(), level.dimension(), tvPos);
        } catch (RuntimeException error) {
            FcArcadeMod.LOGGER.warn("[PIQ FC] Home hardware stop/save request failed at {}", tvPos, error);
        }
    }

    // Package-only: callers must perform both-end protection and identity checks first.
    static void disconnect(HomeEndpointBlockEntity endpoint, ServerPlayer refundTo) {
        if (!(endpoint.getLevel() instanceof ServerLevel level) || endpoint.linkId() == null) return;
        stopEndpoint(endpoint, HomeSystems.StopReason.DISCONNECTED);
        UUID linkId = endpoint.linkId();
        HomeLinkData data = HomeLinkData.get(level);
        var result = data.ledger.close(linkId, endpoint.endpoint());
        if (result == null) { endpoint.clearLink(); return; }
        data.setDirty(); // Claim the one refund before any drop or inventory delivery.
        clearLoadedEnd(level, data, result.link(), result.link().console());
        clearLoadedEnd(level, data, result.link(), result.link().tv());
        if (refundTo != null) HomeInteractionSounds.play(level,endpoint.getBlockPos(),HomeInteractionSounds.Action.CABLE_DISCONNECT);
        if (result.refundCable()) {
            ItemStack returned = new ItemStack(ModItems.AV_CABLE.get());
            if (refundTo != null) giveOrDrop(refundTo, returned);
            else Block.popResource(level, endpoint.getBlockPos(), returned);
        }
    }

    private static void clearLoadedEnd(ServerLevel level, HomeLinkData data,
                                       HomeLinkLedger.Link link, HomeLinkLedger.Endpoint expected) {
        BlockPos pos = new BlockPos(expected.x(), expected.y(), expected.z());
        if (!level.hasChunkAt(pos)) return;
        HomeEndpointBlockEntity actual = loadedEndpoint(level, pos);
        if (actual != null && actual.endpoint().equals(expected) && link.id().equals(actual.linkId())) {
            stopEndpoint(actual);
            actual.clearLink();
        }
        // Loaded replacement/no block means the original endpoint is gone. Its
        // old record may be acknowledged, but the new endpoint must not be edited.
        if (data.ledger.acknowledgeCleared(link.id(), expected)) data.setDirty();
    }

    static void reconcile(HomeEndpointBlockEntity endpoint) {
        if (endpoint instanceof ExternalHomeConsoleBlockEntity external) {
            if (external.isRemoved()) return;
            if (!HomeSystems.complete(external)) stopEndpoint(external);
        }
        if (endpoint instanceof HomeConsoleBlockEntity console) {
            SuborStructure.reconcile(console);
            if (console.isRemoved()) return;
            if (console.getLevel() != null && !SuborStructure.complete(console.getLevel(), console.getBlockPos())) stopEndpoint(console);
        }
        if (endpoint instanceof HomeTvBlockEntity tv) {
            HomeTvStructure.reconcile(tv);
            if (tv.isRemoved()) return;
            if (tv.getLevel() != null && !HomeTvStructure.complete(tv.getLevel(), tv.getBlockPos())) stopEndpoint(tv);
        }
        if (!(endpoint.getLevel() instanceof ServerLevel level) || endpoint.linkId() == null) return;
        HomeLinkData data = HomeLinkData.get(level);
        var link = data.ledger.get(endpoint.linkId());
        if (link == null || !link.owns(endpoint.endpoint())) {
            stopEndpoint(endpoint); endpoint.clearLink(); return;
        }
        if (link.closed()) {
            stopEndpoint(endpoint);
            clearLoadedEnd(level, data, link, link.console());
            clearLoadedEnd(level, data, link, link.tv());
            return;
        }
        var expected = link.other(endpoint.endpoint());
        BlockPos peer = new BlockPos(expected.x(), expected.y(), expected.z());
        if (!level.hasChunkAt(peer)) { stopEndpoint(endpoint); return; }
        HomeEndpointBlockEntity actual = loadedEndpoint(level, peer);
        if (actual == null || !actual.endpoint().equals(expected)
                || !link.id().equals(actual.linkId()) || !endpoint.hardwareId().equals(actual.peerId())
                || !endpoint.linkSpaceValid() || !actual.linkSpaceValid()
                || !(endpoint.kind() == HomeLinkLedger.Kind.CONSOLE ? sameSupportedSpace(endpoint,actual) : sameSupportedSpace(actual,endpoint))) {
            disconnect(endpoint, null);
        }
    }

    static void unloaded(HomeEndpointBlockEntity endpoint) {
        // Unloading is not dismantling: keep link, cartridge and refund state.
        stopEndpoint(endpoint, HomeSystems.StopReason.UNLOADED);
    }

    /** A proxy can break while the TV chunk is unloaded. The existing link
     * ledger alone authorizes the exact endpoint's one cable refund. */
    static void removedTvIdentity(ServerLevel level, BlockPos anchor, UUID hardwareId, BlockPos dropPos) {
        if (level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof HomeTvBlockEntity tv
                && hardwareId.equals(tv.hardwareId())) removed(level, anchor);
        var expected = new HomeLinkLedger.Endpoint(hardwareId, level.dimension().location().toString(),
                anchor.getX(), anchor.getY(), anchor.getZ(), HomeLinkLedger.Kind.TV);
        var data = HomeLinkData.get(level);
        var active = data.ledger.activeFor(expected);
        if (active == null) return;
        var result = data.ledger.close(active.id(), expected);
        if (result == null) return;
        data.setDirty();
        clearLoadedEnd(level, data, result.link(), result.link().console());
        clearLoadedEnd(level, data, result.link(), result.link().tv());
        if (result.refundCable()) Block.popResource(level, dropPos, new ItemStack(ModItems.AV_CABLE.get()));
    }

    /** A four-cell console proxy can disappear while its authoritative cartridge chunk is unloaded. */
    static void removedConsoleIdentity(ServerLevel level, BlockPos anchor, UUID hardwareId, BlockPos dropPos) {
        if (level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof HomeConsoleBlockEntity console
                && hardwareId.equals(console.hardwareId())) removed(level, anchor);
        var expected = new HomeLinkLedger.Endpoint(hardwareId, level.dimension().location().toString(),
                anchor.getX(), anchor.getY(), anchor.getZ(), HomeLinkLedger.Kind.CONSOLE);
        var data = HomeLinkData.get(level);
        var active = data.ledger.activeFor(expected);
        if (active == null) return;
        var result = data.ledger.close(active.id(), expected);
        if (result == null) return;
        data.setDirty();
        clearLoadedEnd(level, data, result.link(), result.link().console());
        clearLoadedEnd(level, data, result.link(), result.link().tv());
        if (result.refundCable()) Block.popResource(level, dropPos, new ItemStack(ModItems.AV_CABLE.get()));
    }

    public static void removed(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel)) return;
        HomeEndpointBlockEntity endpoint = loadedEndpoint(level, pos);
        if (endpoint == null || !endpoint.beginRemoval()) return;
        stopEndpoint(endpoint, HomeSystems.StopReason.REMOVED);
        try { disconnect(endpoint, null); }
        finally {
            if (endpoint instanceof HomeConsoleBlockEntity console)
                Block.popResource(level, pos, console.takeCartridge());
            else if (endpoint instanceof ExternalHomeConsoleBlockEntity external) HomeSystems.removed(external);
        }
    }
}
