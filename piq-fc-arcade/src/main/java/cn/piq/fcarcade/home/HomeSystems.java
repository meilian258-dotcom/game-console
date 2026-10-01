package cn.piq.fcarcade.home;

import cn.piq.fcarcade.FcArcadeMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/** Small external-system bridge; never handles ROM bytes, emulator input or NES sessions. */
public final class HomeSystems {
    public static final ResourceLocation NES_SYSTEM = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "nes");
    private static final HomeSystemRegistry<ResourceLocation, ServerHooks> SYSTEMS = new HomeSystemRegistry<>(NES_SYSTEM);
    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> false);
    private static boolean lifecycleRegistered;
    private static final HomeSystemCalls COMPLETENESS_CALLS = new HomeSystemCalls();
    private static final HomeSystemCalls STOP_CALLS = new HomeSystemCalls();
    private static final HomeSystemCalls REMOVE_CALLS = new HomeSystemCalls();
    private HomeSystems() {}

    public enum StopReason { DISCONNECTED, REMOVED, UNLOADED, INVALID_CONNECTION, POWER_OFF }

    public interface ServerHooks {
        default boolean onPowerOn(ServerPlayer player, Connection connection) { return false; }
        default void onPowerOff(ServerLevel level, ExternalHomeConsoleBlockEntity console) {}
        default void onReset(ServerPlayer player, Connection connection) {}
        default void onController(ServerPlayer player, Connection connection, int port) {}
        /** Physical pickup may happen without power or AV; legacy providers retain their connection contract. */
        default void onControllerDock(ServerPlayer player, ExternalHomeConsoleBlockEntity console, int port) {
            connection(player.serverLevel(), console.getBlockPos()).ifPresent(link -> onController(player, link, port));
        }
        default boolean isRunning(ServerLevel level, ExternalHomeConsoleBlockEntity console) { return false; }
        /** Opt-in: unadapted addons cannot advertise a configurable execution lane. */
        default boolean synchronizationSettingsAvailable() { return false; }
        /** Read-only device diagnostics must not advertise an unsupported execution mode. */
        default boolean deviceSettingsAvailable() { return synchronizationSettingsAvailable(); }
        default String deviceSettingsStatus(ServerLevel level, ExternalHomeConsoleBlockEntity console) {
            return "此设备未开放公共联机模式。";
        }
        default int synchronizationSupportedModes(ServerLevel level, ExternalHomeConsoleBlockEntity console) { return 2; }
        default String synchronizationUnavailableReason(ServerLevel level, ExternalHomeConsoleBlockEntity console, cn.piq.fcarcade.cabinet.CabinetSyncMode mode) { return HomeSyncPolicy.unavailable(mode); }
        /** Includes startup/join/save transactions, not borrowed idle controllers. */
        default boolean synchronizationSettingsBusy(ServerLevel level, ExternalHomeConsoleBlockEntity console) { return true; }
        default void onLinked(ServerPlayer player, Connection connection) {}
        void onInteract(ServerPlayer player, InteractionHand hand, Connection connection, BlockHitResult hit);
        default void onPlaybackStopped(ServerLevel level, ExternalHomeConsoleBlockEntity console, StopReason reason) {}
        default void onRemoved(ServerLevel level, ExternalHomeConsoleBlockEntity console) {}
    }

    /** Call during common setup; the registry locks before the first server starts. */
    public static void register(ResourceLocation systemId, ServerHooks hooks) { SYSTEMS.register(systemId, hooks); }
    static ServerHooks applianceHooks(ResourceLocation id) { return SYSTEMS.get(id); }

    /** Device diagnostics opt-in is independent of server policy disabling public modes. */
    public static boolean privateDeviceSettings(ResourceLocation id) {
        var hooks=SYSTEMS.get(id);
        try { return hooks!=null&&hooks.deviceSettingsAvailable()&&!hooks.synchronizationSettingsAvailable(); }
        catch(RuntimeException|LinkageError unavailable){return false;}
    }

    static synchronized void installLifecycle() {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> SYSTEMS.lock());
    }

    static boolean complete(ExternalHomeConsoleBlockEntity console) {
        if (SYSTEMS.get(console.systemId()) == null || console.isRemoved()) return false;
        boolean[] result = { false };
        return COMPLETENESS_CALLS.invoke(console, () -> result[0] = console.isHardwareComplete(),
                error -> failed(console, "complete", error)) && result[0];
    }

    /** Hardware validity only; callers still need their own player/controller authorization. */
    public static Optional<Connection> connection(ServerLevel level, BlockPos consolePos) {
        if (level == null || consolePos == null || !level.getServer().isSameThread()
                || !(HomeHardware.loadedEndpoint(level, consolePos) instanceof ExternalHomeConsoleBlockEntity console)
                || console.getLevel() != level || console.televisionPos() == null) return Optional.empty();
        if (HomeHardware.connectedEndpoint(level, console.televisionPos()) != console
                || !(HomeHardware.loadedEndpoint(level, console.televisionPos()) instanceof HomeTvBlockEntity television))
            return Optional.empty();
        return Optional.of(new Connection(level, console, television));
    }

    public static Optional<Connection> connectionForTv(ServerLevel level, BlockPos televisionPos) {
        if (level == null || televisionPos == null || !level.getServer().isSameThread()) return Optional.empty();
        var endpoint = HomeHardware.connectedEndpoint(level, televisionPos);
        return endpoint instanceof ExternalHomeConsoleBlockEntity console ? connection(level, console.getBlockPos()) : Optional.empty();
    }

    public static boolean isCurrent(Connection expected) {
        if (expected == null) return false;
        return connection(expected.level(), expected.console().getBlockPos()).filter(current ->
                current.console() == expected.console() && current.television() == expected.television()
                        && current.systemId().equals(expected.systemId()) && current.consoleId().equals(expected.consoleId())
                        && current.televisionId().equals(expected.televisionId()) && current.linkId().equals(expected.linkId())).isPresent();
    }

    /** Called by an external block/item after its own interaction, never grants ROM/controller rights. */
    public static InteractionResult interact(ServerPlayer player, BlockPos consolePos, InteractionHand hand, BlockHitResult hit) {
        if (player == null || consolePos == null || hand == null || hit == null || DISPATCHING.get()) return InteractionResult.CONSUME;
        var current = connection(player.serverLevel(), consolePos);
        if (current.isEmpty()) return denied(player);
        return interactAt(player, consolePos, hand, hit, current.get());
    }

    static InteractionResult interactAt(ServerPlayer player, BlockPos clicked, InteractionHand hand,
                                        BlockHitResult hit, Connection expected) {
        if (DISPATCHING.get() || !hit.getBlockPos().equals(clicked)) return InteractionResult.CONSUME;
        ItemStack held = player.getItemInHand(hand);
        DISPATCHING.set(true);
        try {
            if (!interactionFacts(player, hand, held, clicked, expected)) return denied(player);
            var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, clicked, hit));
            if (event.isCanceled() || event.getUseBlock() == TriState.FALSE || event.getUseItem() == TriState.FALSE
                    || !interactionFacts(player, hand, held, clicked, expected)) return denied(player);
            HomeEndpointBlockEntity clickedEndpoint = HomeHardware.loadedEndpoint(player.serverLevel(), clicked);
            if (clickedEndpoint == null || !HomeHardware.allowAnchorInteraction(player, clicked, clickedEndpoint, hand, hit)
                    || !interactionFacts(player, hand, held, clicked, expected)) return denied(player);
            // Also consult the source console when interaction began on the TV.
            if (clickedEndpoint != expected.console()) {
                var consoleHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), expected.console().getBlockPos(), hit.isInside());
                var sourceEvent = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(
                        player, hand, expected.console().getBlockPos(), consoleHit));
                if (sourceEvent.isCanceled() || sourceEvent.getUseBlock() == TriState.FALSE || sourceEvent.getUseItem() == TriState.FALSE
                        || !interactionFacts(player, hand, held, clicked, expected)) return denied(player);
            }
            ServerHooks hooks = SYSTEMS.get(expected.systemId());
            if (hooks == null) return denied(player);
            hooks.onInteract(player, hand, expected, hit);
        } catch (RuntimeException error) { failed(expected.console(), "interact", error); return denied(player); }
        finally { DISPATCHING.remove(); }
        return InteractionResult.CONSUME;
    }

    private static boolean interactionFacts(ServerPlayer player, InteractionHand hand, ItemStack held,
                                             BlockPos clicked, Connection expected) {
        if (!player.isAlive() || player.isSpectator() || player.hasDisconnected() || player.serverLevel() != expected.level()
                || player.getItemInHand(hand) != held || !HomeHardware.mayUse(player, clicked) || !isCurrent(expected)) return false;
        var endpoint = HomeHardware.loadedEndpoint(player.serverLevel(), clicked);
        return (endpoint == expected.console() || endpoint == expected.television())
                && player.serverLevel().mayInteract(player, expected.console().getBlockPos())
                && player.serverLevel().mayInteract(player, expected.television().getBlockPos());
    }

    static void linked(ServerPlayer player, ExternalHomeConsoleBlockEntity console) {
        if (DISPATCHING.get()) return;
        var current = connection(player.serverLevel(), console.getBlockPos());
        if (current.isEmpty() || current.get().console() != console) return;
        DISPATCHING.set(true);
        try { SYSTEMS.get(console.systemId()).onLinked(player, current.get()); }
        catch (RuntimeException error) { failed(console, "linked", error); }
        finally { DISPATCHING.remove(); }
    }

    static void stopped(ExternalHomeConsoleBlockEntity console, StopReason reason) {
        if (!(console.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        ServerHooks hooks = SYSTEMS.get(console.systemId());
        if (hooks == null) return;
        // Stop does not need a live Connection: an unloaded/missing peer is a reason to stop.
        STOP_CALLS.invoke(console, () -> hooks.onPlaybackStopped(level, console, reason),
                error -> failed(console, "stopped", error));
    }

    static void removed(ExternalHomeConsoleBlockEntity console) {
        if (!(console.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        ServerHooks hooks = SYSTEMS.get(console.systemId());
        if (hooks == null) return;
        REMOVE_CALLS.invoke(console, () -> hooks.onRemoved(level, console), error -> failed(console, "removed", error));
    }

    private static InteractionResult denied(ServerPlayer player) {
        HomeFeedback.show(player, "home_denied"); return InteractionResult.CONSUME;
    }
    private static void failed(ExternalHomeConsoleBlockEntity console, String operation, RuntimeException error) {
        FcArcadeMod.LOGGER.warn("[PIQ FC] External system {} {} failed at {}", console.systemId(), operation, console.getBlockPos(), error);
    }

    /** Snapshot of identities plus live references; never a setter for the AV ledger. */
    public static final class Connection {
        private final ResourceLocation systemId;
        private final ServerLevel level;
        private final ExternalHomeConsoleBlockEntity console;
        private final HomeTvBlockEntity television;
        private final UUID consoleId, televisionId, linkId;
        private Connection(ServerLevel level, ExternalHomeConsoleBlockEntity console, HomeTvBlockEntity television) {
            this.systemId = console.systemId(); this.level = level; this.console = console; this.television = television;
            this.consoleId = console.hardwareId(); this.televisionId = television.hardwareId(); this.linkId = console.linkId();
        }
        public ResourceLocation systemId() { return systemId; }
        public ServerLevel level() { return level; }
        public ExternalHomeConsoleBlockEntity console() { return console; }
        public HomeTvBlockEntity television() { return television; }
        public UUID consoleId() { return consoleId; }
        public UUID televisionId() { return televisionId; }
        public UUID linkId() { return linkId; }
    }
}
