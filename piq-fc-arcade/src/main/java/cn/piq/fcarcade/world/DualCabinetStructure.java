package cn.piq.fcarcade.world;

import cn.piq.fcarcade.registry.ModBlocks;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.world.FcArcadeBlock;
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

/** Exact-owner versioned DualCabinet lifecycle, isolated from all TV structures. */
public final class DualCabinetStructure {
    private static final ThreadLocal<Boolean> CHECKING_ANCHOR = ThreadLocal.withInitial(() -> false);
    private DualCabinetStructure() {}

    public static InteractionResult interact(ServerPlayer player, BlockPos clicked, BlockHitResult hit) {
        var level = player.serverLevel();
        if (!player.getServer().isSameThread() || !level.hasChunkAt(clicked) || !level.mayInteract(player, clicked))
            return InteractionResult.FAIL;
        BlockPos anchor = resolveAnchor(level, clicked);
        if (anchor == null || !complete(level, anchor)) {
            player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.dual_cabinet_incomplete"));
            return InteractionResult.CONSUME;
        }
        if (!level.mayInteract(player, anchor) || !player.mayUseItemAt(anchor, hit.getDirection(), player.getMainHandItem()))
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
                || !level.mayInteract(player, clicked) || !level.mayInteract(player, anchor)) return InteractionResult.FAIL;
        if (cn.piq.fcarcade.cabinet.ServerCabinets.interact(player, clicked, hit)) return InteractionResult.CONSUME;
        if (player.isShiftKeyDown()) ServerArcadeSessions.openLibrary(player, anchor);
        else ServerArcadeSessions.interact(player, anchor);
        return InteractionResult.CONSUME;
    }

    /** Close the exact old anchor only; a replacement arcade must keep its new session. */
    public static void stopSession(ServerLevel level, BlockPos anchor, UUID expected) {
        if (level.hasChunkAt(anchor)) {
            var current = level.getBlockEntity(anchor);
            if (current instanceof DualCabinetBlockEntity cabinet) {
                if (!expected.equals(cabinet.assemblyId())) return;
            } else if (level.getBlockState(anchor).getBlock() instanceof FcArcadeBlock) return;
        }
        try { ServerArcadeSessions.stopHomeConsole(level.getServer(), level.dimension(), anchor); }
        catch (RuntimeException error) {
            cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("[PIQ FC] Cabinet stop/save failed at {}", anchor, error);
        } finally { ServerArcadeSessions.removeMachineDisplays(level.getServer(), level.dimension(), anchor); }
    }
    private static boolean validPart(BlockState state, int part) {
        return part >= 0 && part < DualCabinetFootprint.count(compact(state));
    }

    static boolean compact(BlockState state) {
        return state.hasProperty(DualCabinetBlock.COMPACT) && state.getValue(DualCabinetBlock.COMPACT);
    }

    static DualCabinetFootprint.Facing facing(BlockState state) {
        return DualCabinetFootprint.Facing.valueOf(state.getValue(FcArcadeBlock.FACING).name());
    }

    static BlockPos position(BlockPos anchor, DualCabinetFootprint.Cell cell) {
        return anchor.offset(cell.x(), cell.y(), cell.z());
    }

    static BlockPos anchor(DualCabinetAssemblyLedger.Assembly assembly) {
        return new BlockPos(assembly.x(), assembly.y(), assembly.z());
    }

    static VoxelShape shape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var facing=state.getValue(FcArcadeBlock.FACING);
        int turns=cn.piq.fcarcade.layout.RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ());
        var cell=DualCabinetFootprint.cell(facing(state),part,compact(state));
        var b=cn.piq.fcarcade.layout.DualCabinetGeometry.bounds(turns,compact(state));
        return Shapes.box(b.minX()-cell.x(),b.minY()-cell.y(),b.minZ()-cell.z(),b.maxX()-cell.x(),b.maxY()-cell.y(),b.maxZ()-cell.z());
    }

    static VoxelShape collisionShape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = DualCabinetFootprint.clipped(facing(state), part, compact(state));
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static BlockState partState(BlockState anchor, int part) {
        return ModBlocks.DUAL_CABINET_PART.get().defaultBlockState()
                .setValue(FcArcadeBlock.FACING, anchor.getValue(FcArcadeBlock.FACING))
                .setValue(DualCabinetPartBlock.PART, part)
                .setValue(DualCabinetBlock.COMPACT, compact(anchor));
    }

    /** Resolves loaded proxy clicks only after its BE and physical anchor agree. */
    public static BlockPos resolveAnchor(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (entity instanceof DualCabinetBlockEntity) return pos;
        if (!(entity instanceof DualCabinetPartBlockEntity part) || part.owner() == null || part.anchor() == null
                || !level.hasChunkAt(part.anchor())) return null;
        if (!(level.getBlockEntity(part.anchor()) instanceof DualCabinetBlockEntity tv)
                || !(tv.getBlockState().getBlock() instanceof DualCabinetBlock) || !tv.installed() || !part.owner().equals(tv.assemblyId())) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DualCabinetPartBlock) || facing(state) != facing(tv.getBlockState())
                || compact(state) != tv.compactFootprint()
                || !validPart(state, state.getValue(DualCabinetPartBlock.PART))) return null;
        return position(part.anchor(), DualCabinetFootprint.cell(facing(state), state.getValue(DualCabinetPartBlock.PART), compact(state))).equals(pos)
                ? part.anchor() : null;
    }

    public static boolean complete(Level level, BlockPos anchorPos) {
        if (!level.hasChunkAt(anchorPos) || !(level.getBlockEntity(anchorPos) instanceof DualCabinetBlockEntity tv)) return false;
        if (!(tv.getBlockState().getBlock() instanceof DualCabinetBlock)) return false;
        if (!tv.installed()) return false;
        var facing = facing(tv.getBlockState());
        if (level instanceof ServerLevel server) {
            var data = DualCabinetAssemblyData.get(server);
            if (data.ledger.pending(tv.assemblyId())) return false;
            var entry = data.ledger.get(tv.assemblyId());
            if (entry == null || entry.closed() || !anchor(entry).equals(anchorPos) || entry.facing() != facing
                    || entry.compact() != tv.compactFootprint()) return false;
        }
        for (var cell : DualCabinetFootprint.cells(facing, tv.compactFootprint())) {
            var pos = position(anchorPos, cell);
            if (!level.hasChunkAt(pos)) return false;
            if (cell.part() == 0) continue;
            var state = level.getBlockState(pos);
            if (!(level.getBlockEntity(pos) instanceof DualCabinetPartBlockEntity part)
                    || !tv.assemblyId().equals(part.owner()) || !anchorPos.equals(part.anchor())
                    || !(state.getBlock() instanceof DualCabinetPartBlock)
                    || state.getValue(DualCabinetPartBlock.PART) != cell.part() || facing(state) != facing
                    || compact(state) != tv.compactFootprint()) return false;
        }
        return true;
    }

    static boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!(state.getBlock() instanceof DualCabinetBlock)) return false;
        var level = context.getLevel();
        var player = context.getPlayer();
        var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        return DualCabinetFootprint.canPlace(facing(state), compact(state), cell -> {
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
        var data = DualCabinetAssemblyData.get(server);
        var placed = new ArrayList<Placed>();
        UUID id = null;
        boolean committed = false;
        boolean ledgerAdded = false;
        try {
            DualCabinetBlockEntity tv = null;
            for (var cell : DualCabinetFootprint.cells(facing(state), compact(state))) {
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
                    if (!(entity instanceof DualCabinetBlockEntity created)) return false;
                    tv = created; id = tv.assemblyId(); data.changing.add(id);
                } else {
                    if (!(entity instanceof DualCabinetPartBlockEntity part)) return false;
                    part.attach(id, anchorPos);
                }
            }
            if (tv == null || !data.ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(), facing(state), false, 0, compact(state)))) return false;
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
        if (entity instanceof DualCabinetBlockEntity tv) return new Owner(tv.assemblyId(), tv.getBlockPos(), 0);
        if (entity instanceof DualCabinetPartBlockEntity part && part.owner() != null && part.anchor() != null
                && part.getBlockState().getBlock() instanceof DualCabinetPartBlock)
            return new Owner(part.owner(), part.anchor(), part.getBlockState().getValue(DualCabinetPartBlock.PART));
        return null;
    }

    private static boolean owns(Level level, BlockPos pos, DualCabinetAssemblyLedger.Assembly assembly, int part) {
        if (!level.hasChunkAt(pos)) return false;
        return ownsState(owner(level.getBlockEntity(pos)), level.getBlockState(pos), pos, assembly, part);
    }

    private static boolean ownsState(Owner actual, BlockState state, BlockPos pos,
                                     DualCabinetAssemblyLedger.Assembly assembly, int part) {
        if (!(state.getBlock() instanceof DualCabinetBlock) && !(state.getBlock() instanceof DualCabinetPartBlock)) return false;
        if (facing(state) != assembly.facing() || compact(state) != assembly.compact()) return false;
        return actual != null
                && assembly.id().equals(actual.id()) && actual.anchor().equals(anchor(assembly))
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
            var assembly = DualCabinetAssemblyData.get(server).ledger.get(actual.id());
            if (assembly != null && owns(level, pos, assembly, actual.part())) {
                var result = DualCabinetRemovalGate.attempt(assembly.facing(), assembly.compact(), actual.part(),
                        cell -> level.hasChunkAt(position(actual.anchor(), cell)),
                        cell -> level.mayInteract(player, position(actual.anchor(), cell)),
                        cell -> owns(level, position(actual.anchor(), cell), assembly, cell.part()),
                        cell -> !(player instanceof ServerPlayer serverPlayer) || !CommonHooks.fireBlockBreak(level,
                                serverPlayer.gameMode.getGameModeForPlayer(), serverPlayer,
                                position(actual.anchor(), cell), level.getBlockState(position(actual.anchor(), cell))).isCanceled(),
                        () -> level.hasChunkAt(pos) && level.getBlockState(pos) == state
                                && level.getBlockEntity(pos) == originalEntity, commit);
                if (result == DualCabinetRemovalGate.Result.REMOVED) return true;
                return denyBreak(server, pos, player, result == DualCabinetRemovalGate.Result.DENIED ? "denied" : "incomplete");
            }
        }
        if (level.getBlockState(pos) != state || level.getBlockEntity(pos) != originalEntity)
            return denyBreak(server, pos, player, "incomplete");
        commit.run();
        return true;
    }

    private static boolean denyBreak(ServerLevel level, BlockPos pos, Player player, String message) {
        player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.dual_cabinet_" + message));
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
            var entity = level.getBlockEntity(pos);
            if (entity != null && entity.getUpdatePacket() != null) serverPlayer.connection.send(entity.getUpdatePacket());
        }
        return false;
    }

    static void removed(Level level, BlockPos pos, BlockState removedState) {
        if (!(level instanceof ServerLevel server)) return;
        if (level.restoringBlockSnapshots) {
            // NeoForge cancelled EntityMultiPlaceEvent and is restoring all
            // placement snapshots itself. Never recurse into world cleanup or emit
            // drops/refunds here; the held DualCabinet item has already been restored.
            var actual = owner(level.getBlockEntity(pos));
            var data = DualCabinetAssemblyData.get(server);
            if (actual != null && data.ledger.cancelPlacement(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ()))
                data.setDirty();
            return;
        }
        // onRemove can observe the new world state while the old BE still exists.
        // Its explicit old state, not the replacement, proves the layout entitlement.
        destroy(server, pos, pos, true, removedState);
    }

    static void confirmPlacement(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        if (level.getBlockEntity(pos) instanceof DualCabinetBlockEntity tv) {
            DualCabinetAssemblyData.get(server).ledger.confirmPlacement(tv.assemblyId());
        }
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockPos alreadyRemoving, boolean drop) {
        destroy(level, pos, alreadyRemoving, drop, level.getBlockState(pos));
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockPos alreadyRemoving, boolean drop, BlockState originalState) {
        var entity = level.getBlockEntity(pos);
        var actual = owner(entity);
        if (actual == null) return;
        var data = DualCabinetAssemblyData.get(level);
        if (data.changing.contains(actual.id())) return;
        if (data.ledger.pending(actual.id())) return;
        var entry = data.ledger.get(actual.id());
        if (entry == null || !ownsState(actual, originalState, pos, entry, actual.part())) {
            // An orphan without a ledger never has an item entitlement.
            if (entity instanceof DualCabinetBlockEntity tv) {
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
            if (first && drop) Block.popResource(level, pos, new ItemStack(ModItems.DUAL_CABINET.get()));
        } finally { data.changing.remove(actual.id()); }
    }

    private static void cleanupLoaded(ServerLevel level, DualCabinetAssemblyData data,
                                      DualCabinetAssemblyLedger.Assembly entry, BlockPos alreadyRemoving) {
        if (entry == null || !entry.closed()) return;
        for (var cell : entry.cells()) {
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

    static void reconcile(DualCabinetBlockEntity tv) {
        if (!(tv.getLevel() instanceof ServerLevel level) || !tv.installed()) return;
        confirmPlacement(level, tv.getBlockPos());
        reconcileOwned(level, tv.getBlockPos());
        if (!tv.isRemoved() && !complete(level, tv.getBlockPos())) stopSession(level, tv.getBlockPos(), tv.assemblyId());
    }

    static void reconcile(DualCabinetPartBlockEntity part) {
        if (part.getLevel() instanceof ServerLevel level) reconcileOwned(level, part.getBlockPos());
    }

    private static void reconcileOwned(ServerLevel level, BlockPos pos) {
        var actual = owner(level.getBlockEntity(pos));
        var data = DualCabinetAssemblyData.get(level);
        if (actual != null && data.changing.contains(actual.id())) return;
        var entry = actual == null ? null : data.ledger.get(actual.id());
        if (entry == null || !owns(level, pos, entry, actual.part())) {
            // A stale proxy has no refundable authority. A lone anchor is kept
            // visible for inspection, with playback disabled; no ownership is guessed.
            if (level.getBlockEntity(pos) instanceof DualCabinetPartBlockEntity)
                level.removeBlock(pos, false);
            return;
        }
        if (entry.closed()) { destroy(level, pos, null, false); return; }
        for (var cell : entry.cells()) {
            var other = position(anchor(entry), cell);
            if (!level.hasChunkAt(other)) continue; // Unloading is not dismantling.
            if (!owns(level, other, entry, cell.part())
                    || facing(level.getBlockState(other)) != entry.facing()
                    || (cell.part() == 0 && !(level.getBlockState(other).getBlock() instanceof DualCabinetBlock))) {
                destroy(level, pos, null, true); return;
            }
        }
    }
}
