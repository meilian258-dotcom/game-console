package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.ServerCabinets;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
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

/** The tool never starts/stops a core, grants a controller, changes a backend, or writes a save. */
public final class DeviceDebugService {
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);
    private DeviceDebugService() {}
    public record Selection(Connection connection,ServerLevel level,InteractionHand hand,ItemStack held,ItemStack original,
                            BlockHitResult hit,BlockEntity clickedEntity) {}

    public static <T> T guard(Supplier<T> operation) {
        boolean previous = CHECKING.get();CHECKING.set(true);
        try { return operation.get(); } finally { if (previous) CHECKING.set(true); else CHECKING.remove(); }
    }
    static InteractionResult use(ServerPlayer player,InteractionHand hand,DeviceDebugItem item) {
        if (CHECKING.get()) return InteractionResult.CONSUME;
        var server = player.getServer();var held = player.getItemInHand(hand);
        if (!DeviceDebugPolicy.canActivate(server != null && server.isSameThread(),player.isAlive(),player.isSpectator(),
                !player.hasDisconnected(),player.hasPermissions(2),!held.isEmpty() && held.is(item),player.getCooldowns().isOnCooldown(item),player.isUsingItem()))
            return InteractionResult.CONSUME;
        return guard(() -> {
            // Throttle misses/denials too, before shapes or protection listeners.
            player.getCooldowns().addCooldown(item,DeviceDebugPolicy.COOLDOWN_TICKS);player.startUsingItem(hand);
            try {
                var hit = target(player);if (hit == null) return InteractionResult.CONSUME;
                var selection = new Selection(player.connection.getConnection(),player.serverLevel(),hand,held,held.copy(),hit,
                        player.serverLevel().getBlockEntity(hit.getBlockPos()));
                if (!current(player,selection)) return InteractionResult.CONSUME;
                var home = HomeHardware.loadedEndpoint(player.serverLevel(),hit.getBlockPos());
                if (home instanceof HomeConsoleBlockEntity || home instanceof ExternalHomeConsoleBlockEntity)
                    HomeSyncSettings.openDebug(player,selection);
                else ServerCabinets.openDebugSettings(player,selection);
            } catch (RuntimeException | LinkageError rejected) {
                com.mojang.logging.LogUtils.getLogger().warn("Device debug interaction rejected",rejected);
            }
            return InteractionResult.CONSUME;
        });
    }
    public static boolean current(ServerPlayer player,Selection selected) {
        if (!selectionIdentity(player,selected)) return false;
        var hit = target(player);var clicked = selected.hit().getBlockPos();
        return hit != null && hit.getBlockPos().equals(clicked) && selected.level().getBlockEntity(clicked) == selected.clickedEntity()
                && selected.level().mayInteract(player,clicked) && selectionIdentity(player,selected);
    }
    /** No shapes or provider callbacks; use after ray tracing as well as before it. */
    private static boolean selectionIdentity(ServerPlayer player,Selection selected) {
        var server = player.getServer();
        if (server == null || !server.isSameThread() || player.serverLevel() != selected.level()
                || server.getPlayerList().getPlayer(player.getUUID()) != player || !player.hasPermissions(2) || !player.isAlive() || player.isSpectator()
                || player.hasDisconnected() || player.connection.getConnection() != selected.connection() || !selected.connection().isConnected()
                || player.getItemInHand(selected.hand()) != selected.held() || selected.held().isEmpty()
                || !(selected.held().getItem() instanceof DeviceDebugItem) || !ItemStack.matches(selected.held(),selected.original())) return false;
        return true;
    }
    private static BlockHitResult target(ServerPlayer player) {
        var level = player.serverLevel();Vec3 eye = player.getEyePosition(),look = player.getLookAngle();
        double range = Math.min(DeviceDebugPolicy.RANGE,player.blockInteractionRange());
        if (!finite(eye) || !finite(look) || look.lengthSqr() < .99 || look.lengthSqr() > 1.01 || !Double.isFinite(range) || range <= 0) return null;
        var hit = new LoadedBlocks(level).clip(new ClipContext(eye,eye.add(look.normalize().scale(range)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if (hit.getType() != HitResult.Type.BLOCK || !DeviceDebugPolicy.inRange(eye.distanceToSqr(hit.getLocation()),range)
                || !level.hasChunkAt(hit.getBlockPos()) || !level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return null;
        return hit;
    }
    /** Read-only command selection; grants no tool, settings or gameplay capability. */
    public static BlockHitResult loadedTarget(ServerPlayer player) {
        if(player==null||CHECKING.get()||player.getServer()==null||!player.getServer().isSameThread()
                ||!player.isAlive()||player.isSpectator()||player.hasDisconnected())return null;
        var level=player.serverLevel();var connection=player.connection.getConnection();
        try { return guard(()->{
            var hit=target(player);
            return player.getServer()!=null&&player.getServer().isSameThread()&&player.serverLevel()==level
                    &&player.isAlive()&&!player.isSpectator()&&!player.hasDisconnected()
                    &&player.connection.getConnection()==connection&&connection.isConnected()
                    &&player.getServer().getPlayerList().getPlayer(player.getUUID())==player?hit:null;
        }); } catch(RuntimeException|LinkageError invalid) { return null; }
    }
    private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
    /** Unloaded cells stop the ray, including neighbor queries made by shape callbacks. */
    private record LoadedBlocks(ServerLevel level) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos pos) { return level.hasChunkAt(pos) ? level.getBlockState(pos) : Blocks.BARRIER.defaultBlockState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null; }
        @Override public FluidState getFluidState(BlockPos pos) { return level.hasChunkAt(pos) ? level.getFluidState(pos) : Fluids.EMPTY.defaultFluidState(); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
}
