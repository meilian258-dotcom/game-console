// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import cn.piq.nativearcade.events.NativeCabinetUseEvent;
import cn.piq.nativearcade.events.NativeCabinetClosedEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import cn.piq.nativearcade.world.NativeCabinetBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.neoforged.neoforge.common.CommonHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.UUID;

/** Exact-owner six-cell NativeCabinet lifecycle, isolated from all TV structures. */
public final class NativeCabinetStructure {
    private static final ThreadLocal<Boolean> CHECKING_ANCHOR = ThreadLocal.withInitial(() -> false);
    private NativeCabinetStructure() {}

    public static InteractionResult interact(ServerPlayer player, BlockPos clicked, BlockHitResult hit) {
        var level = player.serverLevel();
        if (!player.getServer().isSameThread() || !level.hasChunkAt(clicked) || !level.mayInteract(player, clicked))
            return InteractionResult.FAIL;
        if (player.distanceToSqr(clicked.getX()+.5,clicked.getY()+.5,clicked.getZ()+.5)>64) return InteractionResult.FAIL;
        BlockPos anchor = resolveAnchor(level, clicked);
        if (anchor == null || !complete(level, anchor)) {
            player.sendSystemMessage(Component.translatable("message.piq_native_arcade.cabinet_incomplete"));
            return InteractionResult.CONSUME;
        }
        if (!usePermitted(player,anchor,hit))
            return InteractionResult.FAIL;
        var cabinet = level.getBlockEntity(anchor);
        var clickedEntity = level.getBlockEntity(clicked);
        if (!clicked.equals(anchor)) {
            if (CHECKING_ANCHOR.get()) return InteractionResult.FAIL;
            CHECKING_ANCHOR.set(true);
            try {
                var anchorHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), anchor, hit.isInside());
                var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, anchor, anchorHit));
                if (event.isCanceled() || event.getUseBlock() == TriState.FALSE || event.getUseItem() == TriState.FALSE)
                    return InteractionResult.FAIL;
            } finally { CHECKING_ANCHOR.remove(); }
        }
        if (!level.hasChunkAt(clicked) || !level.hasChunkAt(anchor) || level.getBlockEntity(anchor) != cabinet
                || level.getBlockEntity(clicked) != clickedEntity || !complete(level, anchor)
                || !usePermitted(player,anchor,hit)) return InteractionResult.FAIL;
        if (!(cabinet instanceof NativeCabinetBlockEntity machine)) return InteractionResult.FAIL;
        var event=NeoForge.EVENT_BUS.post(new NativeCabinetUseEvent(level, player, anchor, machine.assemblyId()));
        if(!event.handled())player.displayClientMessage(Component.translatable("message.piq_native_arcade.preview_unavailable"),true);
        return InteractionResult.CONSUME;
    }

    private static boolean usePermitted(ServerPlayer player,BlockPos anchor,BlockHitResult hit) {
        var level=player.serverLevel();
        if(!level.hasChunkAt(anchor)||!(level.getBlockState(anchor).getBlock() instanceof NativeCabinetBlock))return false;
        for(var cell:NativeCabinetFootprint.cells(facing(level.getBlockState(anchor)))) {
            var pos=position(anchor,cell);
            if(!level.hasChunkAt(pos)||!level.mayInteract(player,pos)
                    ||!player.mayUseItemAt(pos,hit.getDirection(),player.getMainHandItem()))return false;
        }
        return true;
    }

    /** Close the exact old anchor only; a replacement arcade must keep its new session. */
    public static void stopSession(ServerLevel level, BlockPos anchor, UUID expected) {
        if (level.hasChunkAt(anchor)) {
            var current = level.getBlockEntity(anchor);
            if (current instanceof NativeCabinetBlockEntity cabinet) {
                if (!expected.equals(cabinet.assemblyId())) return;
            } else if (level.getBlockState(anchor).getBlock() instanceof NativeCabinetBlock) return;
        }
        try { NeoForge.EVENT_BUS.post(new NativeCabinetClosedEvent(level, anchor, expected)); }
        catch (RuntimeException error) {
            cn.piq.nativearcade.NativeArcadeMod.LOGGER.warn("[PIQ Native] Cabinet session close callback failed at {}", anchor, error);
        }
    }
    private static boolean validPart(BlockState state, int part) {
        return part >= 0 && part < 6;
    }

    static NativeCabinetFootprint.Facing facing(BlockState state) {
        return NativeCabinetFootprint.Facing.valueOf(state.getValue(NativeCabinetBlock.FACING).name());
    }

    static BlockPos position(BlockPos anchor, NativeCabinetFootprint.Cell cell) {
        return anchor.offset(cell.x(), cell.y(), cell.z());
    }

    static BlockPos anchor(NativeCabinetAssemblyLedger.Assembly assembly) {
        return new BlockPos(assembly.x(), assembly.y(), assembly.z());
    }

    static VoxelShape shape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = NativeCabinetFootprint.selection(facing(state), part);
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static VoxelShape collisionShape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = NativeCabinetFootprint.clipped(facing(state), part);
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static BlockState partState(BlockState anchor, int part) {
        return NativeArcadeRegistries.CABINET_PART.get().defaultBlockState()
                .setValue(NativeCabinetBlock.FACING, anchor.getValue(NativeCabinetBlock.FACING))
                .setValue(NativeCabinetPartBlock.PART, part);
    }

    /** Resolves loaded proxy clicks only after its BE and physical anchor agree. */
    public static BlockPos resolveAnchor(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (entity instanceof NativeCabinetBlockEntity) return pos;
        if (!(entity instanceof NativeCabinetPartBlockEntity part) || part.owner() == null || part.anchor() == null
                || !level.hasChunkAt(part.anchor())) return null;
        if (!(level.getBlockEntity(part.anchor()) instanceof NativeCabinetBlockEntity tv)
                || !(tv.getBlockState().getBlock() instanceof NativeCabinetBlock) || !tv.installed() || !part.owner().equals(tv.assemblyId())) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof NativeCabinetPartBlock) || facing(state) != facing(tv.getBlockState())
                || !validPart(state, state.getValue(NativeCabinetPartBlock.PART))) return null;
        return position(part.anchor(), NativeCabinetFootprint.cell(facing(state), state.getValue(NativeCabinetPartBlock.PART))).equals(pos)
                ? part.anchor() : null;
    }

    public static boolean complete(Level level, BlockPos anchorPos) {
        if (!level.hasChunkAt(anchorPos) || !(level.getBlockEntity(anchorPos) instanceof NativeCabinetBlockEntity tv)) return false;
        if (!(tv.getBlockState().getBlock() instanceof NativeCabinetBlock)) return false;
        if (!tv.installed()) return false;
        var facing = facing(tv.getBlockState());
        if (level instanceof ServerLevel server) {
            var data = NativeCabinetAssemblyData.get(server);
            if (data.ledger.pending(tv.assemblyId())) return false;
            var entry = data.ledger.get(tv.assemblyId());
            if (entry == null || entry.closed() || !anchor(entry).equals(anchorPos) || entry.facing() != facing) return false;
        }
        for (var cell : NativeCabinetFootprint.cells(facing)) {
            var pos = position(anchorPos, cell);
            if (!level.hasChunkAt(pos)) return false;
            if (cell.part() == 0) continue;
            var state = level.getBlockState(pos);
            if (!(level.getBlockEntity(pos) instanceof NativeCabinetPartBlockEntity part)
                    || !tv.assemblyId().equals(part.owner()) || !anchorPos.equals(part.anchor())
                    || !(state.getBlock() instanceof NativeCabinetPartBlock)
                    || state.getValue(NativeCabinetPartBlock.PART) != cell.part() || facing(state) != facing) return false;
        }
        return true;
    }

    static boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!(state.getBlock() instanceof NativeCabinetBlock)) return false;
        var level = context.getLevel();
        var player = context.getPlayer();
        var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        return NativeCabinetFootprint.canPlace(facing(state), cell -> {
            var pos = position(context.getClickedPos(), cell);
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) return false;
            if (player != null && (!level.mayInteract(player, pos)
                    || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand()))) return false;
            var cellContext = BlockPlaceContext.at(context, pos, context.getClickedFace());
            return cellContext.getClickedPos().equals(pos) && level.getBlockState(pos).canBeReplaced(cellContext)
                    && level.getBlockEntity(pos) == null
                    && level.isUnobstructed(cell.part() == 0 ? state : partState(state, cell.part()), pos, collision);
        });
    }

    private record Placed(BlockPos pos, BlockState before, BlockState installed, BlockEntity entity) {}

    static boolean place(BlockPlaceContext context, BlockState state) {
        var level = context.getLevel();
        if (!canPlace(context, state)) return false;
        var anchorPos = context.getClickedPos();
        if (!(level instanceof ServerLevel server))
            return level.setBlock(anchorPos, state, Block.UPDATE_CLIENTS);
        if (!server.getServer().isSameThread()) return false;
        var data = NativeCabinetAssemblyData.get(server);
        var placed = new ArrayList<Placed>();
        UUID id = null;
        boolean committed = false;
        boolean ledgerAdded = false;
        try {
            NativeCabinetBlockEntity tv = null;
            for (var cell : NativeCabinetFootprint.cells(facing(state))) {
                var pos = position(anchorPos, cell);
                if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
                if (context.getPlayer() != null && (!level.mayInteract(context.getPlayer(), pos)
                        || !context.getPlayer().mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand()))) return false;
                var installed = cell.part() == 0 ? state : partState(state, cell.part());
                var before = level.getBlockState(pos);
                // Repeat immediately before each write; hooks must not let a
                // different block appear after preflight and then be overwritten.
                var cellContext = BlockPlaceContext.at(context, pos, context.getClickedFace());
                if (!cellContext.getClickedPos().equals(pos) || !before.canBeReplaced(cellContext)
                        || level.getBlockEntity(pos) != null || !level.isUnobstructed(installed, pos,
                        context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer()))) return false;
                if (!level.setBlock(pos, installed, Block.UPDATE_CLIENTS) || !level.hasChunkAt(pos)) return false;
                var entity = level.getBlockEntity(pos);
                placed.add(new Placed(pos, before, installed, entity));
                if (cell.part() == 0) {
                    if (!(entity instanceof NativeCabinetBlockEntity created)) return false;
                    tv = created; id = tv.assemblyId(); data.changing.add(id);
                } else {
                    if (!(entity instanceof NativeCabinetPartBlockEntity part)) return false;
                    part.attach(id, anchorPos);
                }
            }
            if (tv == null || !data.ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(), facing(state), false, 0))) return false;
            ledgerAdded = true;
            if (level.captureBlockSnapshots) data.ledger.awaitPlacementEvent(id);
            tv.install(); data.setDirty(); committed = true;
            if (!level.captureBlockSnapshots)
                for (var placedCell : placed) level.blockUpdated(placedCell.pos(), placedCell.installed().getBlock());
            return true;
        } finally {
            if (!committed) {
                // Only this exact just-created BE/state may be restored. No drops,
                // no displacement of a replacement introduced by another hook.
                for (int i = placed.size() - 1; i >= 0; i--) {
                    var cell = placed.get(i);
                    if (level.hasChunkAt(cell.pos()) && level.getBlockState(cell.pos()) == cell.installed()
                            && level.getBlockEntity(cell.pos()) == cell.entity())
                        level.setBlock(cell.pos(), cell.before(), Block.UPDATE_ALL);
                }
            }
            if (!committed && ledgerAdded) {
                data.ledger.awaitPlacementEvent(id);
                data.ledger.cancelPlacement(id, 0, anchorPos.getX(), anchorPos.getY(), anchorPos.getZ());
                data.setDirty();
            }
            if (id != null) data.changing.remove(id);
        }
    }

    private record Owner(UUID id, BlockPos anchor, int part) {}

    private static Owner owner(BlockEntity entity) {
        if (entity instanceof NativeCabinetBlockEntity tv) return new Owner(tv.assemblyId(), tv.getBlockPos(), 0);
        if (entity instanceof NativeCabinetPartBlockEntity part && part.owner() != null && part.anchor() != null
                && part.getBlockState().getBlock() instanceof NativeCabinetPartBlock)
            return new Owner(part.owner(), part.anchor(), part.getBlockState().getValue(NativeCabinetPartBlock.PART));
        return null;
    }

    private static boolean owns(Level level, BlockPos pos, NativeCabinetAssemblyLedger.Assembly assembly, int part) {
        if (!level.hasChunkAt(pos)) return false;
        var actual = owner(level.getBlockEntity(pos));
        return actual != null && assembly.id().equals(actual.id()) && actual.anchor().equals(anchor(assembly))
                && actual.part() == part && assembly.owns(part, pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean breakByPlayer(Level level, BlockPos pos, BlockState state, Player player,
                                 boolean willHarvest, FluidState fluid) {
        if (!(level instanceof ServerLevel server)) return level.setBlock(pos, fluid.createLegacyBlock(), 11);
        if (!level.mayInteract(player, pos)) return denyBreak(server, pos, player, "denied");
        var originalEntity = level.getBlockEntity(pos);
        var actual = owner(originalEntity);
        Runnable commit = () -> {
            destroy(server, pos, null, willHarvest && !player.isCreative());
            // Orphan proxies may be removed, but never a same-type replacement
            // installed by a neighbour callback while cleaning the other cells.
            if (level.getBlockState(pos) == state && level.getBlockEntity(pos) == originalEntity)
                level.removeBlock(pos, false);
        };
        if (actual != null) {
            var assembly = NativeCabinetAssemblyData.get(server).ledger.get(actual.id());
            if (assembly != null && actual.anchor().equals(anchor(assembly))
                    && assembly.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
                var result = NativeCabinetRemovalGate.attempt(assembly.facing(), actual.part(),
                        cell -> level.hasChunkAt(position(actual.anchor(), cell)),
                        cell -> level.mayInteract(player, position(actual.anchor(), cell)),
                        cell -> owns(level, position(actual.anchor(), cell), assembly, cell.part()),
                        cell -> !(player instanceof ServerPlayer serverPlayer) || !CommonHooks.fireBlockBreak(level,
                                serverPlayer.gameMode.getGameModeForPlayer(), serverPlayer,
                                position(actual.anchor(), cell), level.getBlockState(position(actual.anchor(), cell))).isCanceled(),
                        () -> level.hasChunkAt(pos) && level.getBlockState(pos) == state
                                && level.getBlockEntity(pos) == originalEntity, commit);
                if (result == NativeCabinetRemovalGate.Result.REMOVED) return true;
                return denyBreak(server, pos, player, result == NativeCabinetRemovalGate.Result.DENIED ? "denied" : "incomplete");
            }
        }
        if (level.getBlockState(pos) != state || level.getBlockEntity(pos) != originalEntity)
            return denyBreak(server, pos, player, "incomplete");
        commit.run();
        return true;
    }

    private static boolean denyBreak(ServerLevel level, BlockPos pos, Player player, String message) {
        player.sendSystemMessage(Component.translatable("message.piq_native_arcade.cabinet_" + message));
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
            var entity = level.getBlockEntity(pos);
            if (entity != null && entity.getUpdatePacket() != null) serverPlayer.connection.send(entity.getUpdatePacket());
        }
        return false;
    }

    static void removed(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return;
        if (level.restoringBlockSnapshots) {
            // NeoForge cancelled EntityMultiPlaceEvent and is restoring all
            // placement snapshots itself. Never recurse into world cleanup or emit
            // drops/refunds here; the held NativeCabinet item has already been restored.
            var actual = owner(level.getBlockEntity(pos));
            var data = NativeCabinetAssemblyData.get(server);
            if (actual != null && data.ledger.cancelPlacement(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ()))
                data.setDirty();
            return;
        }
        destroy(server, pos, pos, true);
    }

    static void confirmPlacement(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        if (level.getBlockEntity(pos) instanceof NativeCabinetBlockEntity tv) {
            NativeCabinetAssemblyData.get(server).ledger.confirmPlacement(tv.assemblyId());
        }
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockPos alreadyRemoving, boolean drop) {
        var entity = level.getBlockEntity(pos);
        var actual = owner(entity);
        if (actual == null) return;
        var data = NativeCabinetAssemblyData.get(level);
        if (data.changing.contains(actual.id())) return;
        if (data.ledger.pending(actual.id())) return;
        var entry = data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // An orphan without a ledger never has an item entitlement.
            if (entity instanceof NativeCabinetBlockEntity tv) {
                stopSession(level, pos, tv.assemblyId());
                if (alreadyRemoving == null && level.getBlockEntity(pos) == tv) level.removeBlock(pos, false);
                // A managed orphan with no ledger may be an incomplete snapshot
                // rollback (held item already returned); never mint another cabinet.

            }
            return;
        }
        boolean first = data.ledger.close(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ());
        data.setDirty(); // Claim once before the cabinet item is emitted.
        data.changing.add(actual.id());
        try {
            stopSession(level, actual.anchor(), actual.id());
            cleanupLoaded(level, data, data.ledger.get(actual.id()), alreadyRemoving);
            if (first && drop) Block.popResource(level, pos, new ItemStack(NativeArcadeRegistries.CABINET_ITEM.get()));
        } finally { data.changing.remove(actual.id()); }
    }

    private static void cleanupLoaded(ServerLevel level, NativeCabinetAssemblyData data,
                                      NativeCabinetAssemblyLedger.Assembly entry, BlockPos alreadyRemoving) {
        if (entry == null || !entry.closed()) return;
        for (var cell : NativeCabinetFootprint.cells(entry.facing())) {
            var pos = position(anchor(entry), cell);
            if (!level.hasChunkAt(pos)) continue;
            if (owns(level, pos, entry, cell.part()) && !pos.equals(alreadyRemoving)) {
                var original = level.getBlockEntity(pos);
                if (cell.part() == 0) stopSession(level, pos, entry.id());
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) == original)
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            // A loaded foreign replacement is acknowledged, never deleted.
            if (level.hasChunkAt(pos)) { data.ledger.acknowledge(entry.id(), cell.part()); data.setDirty(); }
        }
    }

    static void reconcile(NativeCabinetBlockEntity tv) {
        if (!(tv.getLevel() instanceof ServerLevel level) || !tv.installed()) return;
        confirmPlacement(level, tv.getBlockPos());
        reconcileOwned(level, tv.getBlockPos());
        if (!tv.isRemoved() && !complete(level, tv.getBlockPos())) stopSession(level, tv.getBlockPos(), tv.assemblyId());
    }

    static void reconcile(NativeCabinetPartBlockEntity part) {
        if (part.getLevel() instanceof ServerLevel level) reconcileOwned(level, part.getBlockPos());
    }

    private static void reconcileOwned(ServerLevel level, BlockPos pos) {
        var actual = owner(level.getBlockEntity(pos));
        var data = NativeCabinetAssemblyData.get(level);
        if (actual != null && data.changing.contains(actual.id())) return;
        var entry = actual == null ? null : data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // A stale proxy has no refundable authority. A lone anchor is kept
            // visible for inspection, with playback disabled; no ownership is guessed.
            if (level.getBlockEntity(pos) instanceof NativeCabinetPartBlockEntity)
                level.removeBlock(pos, false);
            return;
        }
        if (entry.closed()) { destroy(level, pos, null, false); return; }
        for (var cell : NativeCabinetFootprint.cells(entry.facing())) {
            var other = position(anchor(entry), cell);
            if (!level.hasChunkAt(other)) continue; // Unloading is not dismantling.
            if (!owns(level, other, entry, cell.part())
                    || facing(level.getBlockState(other)) != entry.facing()
                    || (cell.part() == 0 && !(level.getBlockState(other).getBlock() instanceof NativeCabinetBlock))) {
                destroy(level, pos, null, true); return;
            }
        }
    }
}
