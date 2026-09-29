package cn.piq.fcarcade.home;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server-authoritative presentation-only action, shared by direct and ranged use. */
@net.neoforged.fml.common.EventBusSubscriber(modid="piq_fc_arcade")
public final class TvRemoteService {
    private static final ThreadLocal<Boolean> CHECKING_REMOTE = ThreadLocal.withInitial(() -> false);
    private static final java.util.Map<ServerPlayer, Intent> INTENTS = new java.util.WeakHashMap<>();
    private TvRemoteService() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void loggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) INTENTS.remove(player);
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        INTENTS.entrySet().removeIf(entry -> entry.getValue().target.level().getServer() == event.getServer());
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 0) return;
        INTENTS.entrySet().removeIf(entry -> entry.getValue().target.level().getServer() == event.getServer()
            && (entry.getKey().hasDisconnected() || entry.getValue().expires < event.getServer().getTickCount()));
    }

    public static InteractionResult use(ServerPlayer player, InteractionHand hand, TvRemoteItem remote) {
        if (CHECKING_REMOTE.get()) return InteractionResult.CONSUME;
        ItemStack held = player.getItemInHand(hand);
        if (!TvRemotePolicy.canActivate(player.getServer() != null && player.getServer().isSameThread(),
                player.isAlive(), player.isSpectator(), !player.hasDisconnected(),
                !held.isEmpty() && held.is(remote), player.getCooldowns().isOnCooldown(remote), player.isUsingItem()))
            return InteractionResult.CONSUME;
        CHECKING_REMOTE.set(true);
        try {
            // Cool down attempts too; a held key or denied target must not flood
            // protection listeners. The vanilla use latch lasts until release.
            player.getCooldowns().addCooldown(remote, TvRemotePolicy.COOLDOWN_TICKS);
            player.startUsingItem(hand);
            if (!player.isUsingItem() || !stillHolding(player, hand, held)) return InteractionResult.CONSUME;
            Target target = target(player);
            if (target == null) return feedback(player, "tv_remote_no_tv");
            if (!permitted(player, hand, held, target)) return feedback(player, "tv_remote_denied");
            INTENTS.entrySet().removeIf(entry -> entry.getKey().hasDisconnected()
                    || entry.getKey().getServer() == null || entry.getValue().expires < entry.getKey().getServer().getTickCount());
            if (INTENTS.size() >= 256 && !INTENTS.containsKey(player)) return feedback(player, "tv_remote_denied");
            var intent = new Intent(player, hand, held, target);
            INTENTS.put(player, intent);
            reply(player, intent, true, "仅调整当前电视；主机高级设置请使用调试螺丝刀，存档功能以对应主机说明为准。");
            return InteractionResult.CONSUME;
        } catch (RuntimeException error) {
            // Third-party shape/protection callbacks must fail closed, never turn
            // a failed authorization into a toggle or break the server tick.
            LogUtils.getLogger().warn("FC TV remote interaction was rejected", error);
            return feedback(player, "tv_remote_denied");
        } finally { CHECKING_REMOTE.remove(); }
    }

    private static final class Intent {
        final UUID token = UUID.randomUUID();
        final net.minecraft.network.Connection connection;
        final InteractionHand hand;
        final ItemStack held;
        final Target target;
        final HomeEndpointBlockEntity console;
        final UUID link;
        final long expires;
        int revision;
        long lastRequest = Long.MIN_VALUE;
        Intent(ServerPlayer player, InteractionHand hand, ItemStack held, Target target) {
            this.connection = player.connection.getConnection(); this.hand = hand; this.held = held; this.target = target;
            console = HomeHardware.connectedEndpoint(player.serverLevel(), target.tv().getBlockPos());
            link = target.tv().linkId(); expires = player.getServer().getTickCount() + 1200L;
        }
    }

    static void request(ServerPlayer player, TvRemoteNetwork.Request packet) {
        if (CHECKING_REMOTE.get()) return;
        var intent = INTENTS.get(player);
        if (intent == null || !intent.token.equals(packet.token())) return;
        if (player.getServer() == null || !player.getServer().isSameThread()
                || player.connection.getConnection() != intent.connection || !intent.connection.isConnected()) return;
        long now = player.getServer().getTickCount();
        // Bound work before ray tracing or third-party permission callbacks.
        if (intent.lastRequest != Long.MIN_VALUE && now - intent.lastRequest < 3) {
            // Client ticks can outrun a slow server. A cheap negative acknowledgement
            // releases the UI without invoking another ray/protection callback.
            reply(player, intent, false, "操作过快，设置未修改；请稍后重试。"); return;
        }
        intent.lastRequest = now;
        CHECKING_REMOTE.set(true);
        try {
            if (!current(player, intent) || !permitted(player, intent.hand, intent.held, intent.target)
                    || !consolePermitted(player, intent) || !current(player, intent)) {
                INTENTS.remove(player); return;
            }
            if (packet.revision() != intent.revision) { reply(player, intent, false, "状态已更新，请核对后再调整。"); return; }
            int action = packet.action(), value = packet.value();
            if (!TvRemoteSettingsPolicy.mayApply(action, value, intent.console != null, player.hasPermissions(2))) {
                reply(player, intent, false, "遥控器仅调整电视；主机高级设置请使用调试螺丝刀。"); return;
            }
            var tv = intent.target.tv(); boolean on = value != 0;
            switch (action) {
                case TvRemoteSettingsPolicy.SCANLINES -> tv.setScanlinesEnabled(on);
                case TvRemoteSettingsPolicy.VOLUME -> tv.setVolume(value);
                case TvRemoteSettingsPolicy.MUTE -> tv.setMuted(on);
                case TvRemoteSettingsPolicy.ANIMATION -> tv.setPowerAnimationEnabled(on);
                case TvRemoteSettingsPolicy.NO_SIGNAL_TONE -> tv.setNoSignalToneEnabled(on);
                default -> { }
            }
            if (action != TvRemoteSettingsPolicy.REFRESH) intent.revision++;
            reply(player, intent, false, action == TvRemoteSettingsPolicy.REFRESH ? "已刷新当前状态。" : "已保存；不会修改游戏进度或存档。");
        } catch (RuntimeException | LinkageError rejected) {
            INTENTS.remove(player);
            LogUtils.getLogger().warn("TV remote settings rejected", rejected);
        } finally { CHECKING_REMOTE.remove(); }
    }

    private static boolean current(ServerPlayer player, Intent i) {
        return player.getServer() != null && player.getServer().isSameThread()
            && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
            && player.connection.getConnection() == i.connection && i.connection.isConnected()
            && player.getServer().getTickCount() <= i.expires && sameTarget(player, i.hand, i.held, i.target)
            && java.util.Objects.equals(i.link, i.target.tv().linkId())
            && HomeHardware.connectedEndpoint(player.serverLevel(), i.target.tv().getBlockPos()) == i.console;
    }

    private static boolean consolePermitted(ServerPlayer player, Intent i) {
        if (i.console == null) return true;
        var c = i.console; var pos = c.getBlockPos();
        if (c.isRemoved() || c.getLevel() != player.serverLevel() || !player.serverLevel().hasChunkAt(pos)
            || player.serverLevel().getBlockEntity(pos) != c || !player.serverLevel().mayInteract(player, pos)
            || !player.serverLevel().getWorldBorder().isWithinBounds(pos)) return false;
        var hit = new BlockHitResult(i.target.hit().getLocation(), i.target.hit().getDirection(), pos, false);
        var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, i.hand, pos, hit));
        return !event.isCanceled() && event.getUseBlock() != TriState.FALSE && event.getUseItem() != TriState.FALSE
            && current(player, i) && player.serverLevel().mayInteract(player, pos)
            && HomeHardware.allowAnchorInteraction(player, pos, c, i.hand, hit) && current(player, i);
    }

    private static void reply(ServerPlayer player, Intent i, boolean open, String reason) {
        var tv = i.target.tv(); var c = i.console;
        TvRemoteNetwork.send(player, new TvRemoteNetwork.Setting(i.token, i.revision,
            player.serverLevel().dimension().location(), tv.getBlockPos(), tv.hardwareId(),
            tv.scanlinesEnabled(), tv.volume(), tv.muted(), tv.powerAnimationEnabled(), tv.noSignalToneEnabled(),
            c == null || c.occupancyVisible(), c == null || c.joinApprovalRequired(), c != null,
            player.hasPermissions(2), reason, open));
    }

    private static boolean stillHolding(ServerPlayer player, InteractionHand hand, ItemStack held) {
        return player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && player.getItemInHand(hand) == held && !held.isEmpty() && held.getItem() instanceof TvRemoteItem;
    }

    private static Target target(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
        if (!finite(eye) || !finite(look) || look.lengthSqr() < 0.99 || look.lengthSqr() > 1.01) return null;
        Vec3 end = eye.add(look.normalize().scale(TvRemotePolicy.RANGE));
        // A guarded BlockGetter also covers shape implementations that query
        // neighboring blocks. Ordinary Level.clip may load those chunks.
        BlockHitResult hit = new LoadedBlockView(level).clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !TvRemotePolicy.inRange(eye.distanceToSqr(hit.getLocation()))) return null;
        BlockPos clicked = hit.getBlockPos();
        if (!level.hasChunkAt(clicked) || !level.getWorldBorder().isWithinBounds(clicked)
                || !(HomeHardware.loadedEndpoint(level, clicked) instanceof HomeTvBlockEntity tv)
                || tv.getLevel() != level || !level.getWorldBorder().isWithinBounds(tv.getBlockPos())
                || !HomeTvStructure.complete(level, tv.getBlockPos())) return null;
        return new Target(level, hit, tv, tv.hardwareId(), level.getBlockEntity(clicked));
    }

    private static boolean permitted(ServerPlayer player, InteractionHand hand, ItemStack held, Target expected) {
        if (!sameTarget(player, hand, held, expected)) return false;
        // Air use has no vanilla RightClickBlock event at this ranged target.
        // Always consult the clicked cell; proxies also consult their anchor.
        var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(
                player, hand, expected.hit().getBlockPos(), expected.hit()));
        if (event.isCanceled() || event.getUseBlock() == TriState.FALSE || event.getUseItem() == TriState.FALSE
                || !sameTarget(player, hand, held, expected)) return false;
        if (!HomeHardware.allowAnchorInteraction(player, expected.hit().getBlockPos(), expected.tv(), hand, expected.hit())) return false;
        // Listeners may have teleported the player, moved/replaced the machine,
        // changed the held stack, or placed a wall. Never trust pre-event facts.
        return sameTarget(player, hand, held, expected);
    }

    private static boolean sameTarget(ServerPlayer player, InteractionHand hand, ItemStack held, Target expected) {
        if (player.serverLevel() != expected.level() || !stillHolding(player, hand, held)) return false;
        Target current = target(player);
        return current != null && current.tv() == expected.tv() && current.identity().equals(expected.identity())
                && current.hit().getBlockPos().equals(expected.hit().getBlockPos())
                && current.clickedEntity() == expected.clickedEntity()
                && current.level().mayInteract(player, current.hit().getBlockPos())
                && current.level().mayInteract(player, current.tv().getBlockPos());
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static InteractionResult feedback(ServerPlayer player, String key) {
        HomeFeedback.show(player, key);
        return InteractionResult.CONSUME;
    }

    private record Target(ServerLevel level, BlockHitResult hit, HomeTvBlockEntity tv, UUID identity,
                          BlockEntity clickedEntity) {}

    /** Missing chunks stop the ray rather than being loaded by it. */
    private record LoadedBlockView(ServerLevel level) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos pos) {
            return level.hasChunkAt(pos) ? level.getBlockState(pos) : Blocks.BARRIER.defaultBlockState();
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return level.hasChunkAt(pos) ? level.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
        }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
}
