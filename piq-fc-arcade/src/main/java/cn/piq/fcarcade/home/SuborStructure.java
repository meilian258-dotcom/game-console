package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlocks;
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

/** Exact-owner versioned Subor lifecycle, isolated from all TV structures. */
public final class SuborStructure {
    private SuborStructure() {}
    private static boolean validPart(BlockState state, int part) {
        return part >= 0 && part < SuborFootprint.partCount(SuborConsoleBlock.compact(state));
    }

    static SuborFootprint.Facing facing(BlockState state) {
        return SuborFootprint.Facing.valueOf(state.getValue(FcArcadeBlock.FACING).name());
    }

    static BlockPos position(BlockPos anchor, SuborFootprint.Cell cell) {
        return anchor.offset(cell.x(), cell.y(), cell.z());
    }

    static BlockPos anchor(SuborAssemblyLedger.Assembly assembly) {
        return new BlockPos(assembly.x(), assembly.y(), assembly.z());
    }

    static VoxelShape shape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = SuborFootprint.clipped(facing(state), part, SuborConsoleBlock.compact(state));
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static VoxelShape collisionShape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = SuborFootprint.clipped(facing(state), part, SuborConsoleBlock.compact(state));
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static BlockState partState(BlockState anchor, int part) {
        return ModBlocks.SUBOR_PART.get().defaultBlockState()
                .setValue(FcArcadeBlock.FACING, anchor.getValue(FcArcadeBlock.FACING))
                .setValue(SuborConsoleBlock.COMPACT, SuborConsoleBlock.compact(anchor))
                .setValue(SuborPartBlock.PART, part);
    }

    /** Resolves loaded proxy clicks only after its BE and physical anchor agree. */
    public static BlockPos resolveAnchor(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (entity instanceof HomeConsoleBlockEntity) return pos;
        if (!(entity instanceof SuborPartBlockEntity part) || part.owner() == null || part.anchor() == null
                || !level.hasChunkAt(part.anchor())) return null;
        if (!(level.getBlockEntity(part.anchor()) instanceof HomeConsoleBlockEntity tv)
                || !SuborConsoleBlock.wide(tv.getBlockState()) || !tv.suborStructureInstalled() || !part.owner().equals(tv.hardwareId())) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SuborPartBlock) || facing(state) != facing(tv.getBlockState())
                || SuborConsoleBlock.compact(state) != SuborConsoleBlock.compact(tv.getBlockState())
                || !validPart(state, state.getValue(SuborPartBlock.PART))) return null;
        return position(part.anchor(), SuborFootprint.cell(facing(state), state.getValue(SuborPartBlock.PART),
                SuborConsoleBlock.compact(state))).equals(pos)
                ? part.anchor() : null;
    }

    public static boolean complete(Level level, BlockPos anchorPos) {
        if (!level.hasChunkAt(anchorPos) || !(level.getBlockEntity(anchorPos) instanceof HomeConsoleBlockEntity tv)) return false;
        if (!SuborConsoleBlock.wide(tv.getBlockState())) return !tv.suborStructureInstalled();
        if (!tv.suborStructureInstalled()) return false;
        var facing = facing(tv.getBlockState());
        boolean compact = SuborConsoleBlock.compact(tv.getBlockState());
        if (level instanceof ServerLevel server) {
            var data = SuborAssemblyData.get(server);
            if (data.ledger.pending(tv.hardwareId())) return false;
            var entry = data.ledger.get(tv.hardwareId());
            if (entry == null || entry.closed() || !anchor(entry).equals(anchorPos) || entry.facing() != facing
                    || entry.compact() != compact) return false;
        }
        for (var cell : SuborFootprint.cells(facing, compact)) {
            var pos = position(anchorPos, cell);
            if (!level.hasChunkAt(pos)) return false;
            if (cell.part() == 0) continue;
            var state = level.getBlockState(pos);
            if (!(level.getBlockEntity(pos) instanceof SuborPartBlockEntity part)
                    || !tv.hardwareId().equals(part.owner()) || !anchorPos.equals(part.anchor())
                    || !(state.getBlock() instanceof SuborPartBlock)
                    || SuborConsoleBlock.compact(state) != compact
                    || state.getValue(SuborPartBlock.PART) != cell.part() || facing(state) != facing) return false;
        }
        return true;
    }

    static boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!SuborConsoleBlock.wide(state)) return false;
        var level = context.getLevel();
        var player = context.getPlayer();
        var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        return SuborFootprint.canPlace(facing(state), SuborConsoleBlock.compact(state), cell -> {
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
        var data = SuborAssemblyData.get(server);
        var placed = new ArrayList<Placed>();
        UUID id = null;
        boolean committed = false;
        boolean ledgerAdded = false;
        try {
            HomeConsoleBlockEntity tv = null;
            for (var cell : SuborFootprint.cells(facing(state), SuborConsoleBlock.compact(state))) {
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
                    if (!(entity instanceof HomeConsoleBlockEntity created)) return false;
                    tv = created; id = tv.hardwareId(); data.changing.add(id);
                } else {
                    if (!(entity instanceof SuborPartBlockEntity part)) return false;
                    part.attach(id, anchorPos);
                }
            }
            if (tv == null || !data.ledger.restore(new SuborAssemblyLedger.Assembly(id,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(), facing(state),
                    SuborConsoleBlock.compact(state), false, 0))) return false;
            ledgerAdded = true;
            if (level.captureBlockSnapshots) data.ledger.awaitPlacementEvent(id);
            tv.installSuborStructure(); data.setDirty(); committed = true;
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
        if (entity instanceof HomeConsoleBlockEntity tv) return new Owner(tv.hardwareId(), tv.getBlockPos(), 0);
        if (entity instanceof SuborPartBlockEntity part && part.owner() != null && part.anchor() != null
                && part.getBlockState().getBlock() instanceof SuborPartBlock)
            return new Owner(part.owner(), part.anchor(), part.getBlockState().getValue(SuborPartBlock.PART));
        return null;
    }

    private static boolean owns(Level level, BlockPos pos, SuborAssemblyLedger.Assembly assembly, int part) {
        if (!level.hasChunkAt(pos)) return false;
        var actual = owner(level.getBlockEntity(pos));
        return actual != null && assembly.id().equals(actual.id()) && actual.anchor().equals(anchor(assembly))
                && SuborConsoleBlock.compact(level.getBlockState(pos)) == assembly.compact()
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
            var assembly = SuborAssemblyData.get(server).ledger.get(actual.id());
            if (assembly != null && actual.anchor().equals(anchor(assembly))
                    && SuborConsoleBlock.compact(state) == assembly.compact()
                    && assembly.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
                var result = SuborRemovalGate.attempt(assembly.facing(), assembly.compact(), actual.part(),
                        cell -> level.hasChunkAt(position(actual.anchor(), cell)),
                        cell -> level.mayInteract(player, position(actual.anchor(), cell)),
                        cell -> owns(level, position(actual.anchor(), cell), assembly, cell.part()),
                        cell -> !(player instanceof ServerPlayer serverPlayer) || !CommonHooks.fireBlockBreak(level,
                                serverPlayer.gameMode.getGameModeForPlayer(), serverPlayer,
                                position(actual.anchor(), cell), level.getBlockState(position(actual.anchor(), cell))).isCanceled(),
                        () -> level.hasChunkAt(pos) && level.getBlockState(pos) == state
                                && level.getBlockEntity(pos) == originalEntity, commit);
                if (result == SuborRemovalGate.Result.REMOVED) return true;
                return denyBreak(server, pos, player, result == SuborRemovalGate.Result.DENIED ? "denied" : "subor_incomplete");
            }
        }
        if (level.getBlockState(pos) != state || level.getBlockEntity(pos) != originalEntity)
            return denyBreak(server, pos, player, "subor_incomplete");
        commit.run();
        return true;
    }

    private static boolean denyBreak(ServerLevel level, BlockPos pos, Player player, String message) {
        player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.home_" + message));
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
            // drops/refunds here; the held Subor item has already been restored.
            var actual = owner(level.getBlockEntity(pos));
            var data = SuborAssemblyData.get(server);
            if (actual != null && data.ledger.cancelPlacement(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ()))
                data.setDirty();
            return;
        }
        destroy(server, pos, pos, true);
    }

    static void confirmPlacement(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        if (level.getBlockEntity(pos) instanceof HomeConsoleBlockEntity tv) {
            SuborAssemblyData.get(server).ledger.confirmPlacement(tv.hardwareId());
        }
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockPos alreadyRemoving, boolean drop) {
        var entity = level.getBlockEntity(pos);
        var actual = owner(entity);
        if (actual == null) return;
        var data = SuborAssemblyData.get(level);
        if (data.changing.contains(actual.id())) return;
        if (data.ledger.pending(actual.id())) return;
        var entry = data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || SuborConsoleBlock.compact(entity.getBlockState()) != entry.compact()
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // A wide orphan without a ledger never has an item entitlement.
            if (entity instanceof HomeConsoleBlockEntity tv) {
                HomeHardware.removed(level, pos);
                if (alreadyRemoving == null && level.getBlockEntity(pos) == tv) level.removeBlock(pos, false);
                // A managed orphan with no ledger may be an incomplete snapshot
                // rollback (held item already returned); never mint another console.

            }
            return;
        }
        boolean first = data.ledger.close(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ());
        data.setDirty(); // Claim once before either console or AV cable is emitted.
        data.changing.add(actual.id());
        try {
            HomeHardware.removedConsoleIdentity(level, actual.anchor(), actual.id(), pos);
            cleanupLoaded(level, data, data.ledger.get(actual.id()), alreadyRemoving);
            if (first && drop) Block.popResource(level, pos, new ItemStack(ModItems.SUBOR_CONSOLE.get()));
        } finally { data.changing.remove(actual.id()); }
    }

    private static void cleanupLoaded(ServerLevel level, SuborAssemblyData data,
                                      SuborAssemblyLedger.Assembly entry, BlockPos alreadyRemoving) {
        if (entry == null || !entry.closed()) return;
        for (var cell : SuborFootprint.cells(entry.facing(), entry.compact())) {
            var pos = position(anchor(entry), cell);
            if (!level.hasChunkAt(pos)) continue;
            if (owns(level, pos, entry, cell.part()) && !pos.equals(alreadyRemoving)) {
                var original = level.getBlockEntity(pos);
                if (cell.part() == 0) HomeHardware.removed(level, pos);
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) == original)
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            // A loaded foreign replacement is acknowledged, never deleted.
            if (level.hasChunkAt(pos)) { data.ledger.acknowledge(entry.id(), cell.part()); data.setDirty(); }
        }
    }

    static void reconcile(HomeConsoleBlockEntity tv) {
        if (!(tv.getLevel() instanceof ServerLevel level) || !tv.suborStructureInstalled()) return;
        confirmPlacement(level, tv.getBlockPos());
        reconcileOwned(level, tv.getBlockPos());
    }

    static void reconcile(SuborPartBlockEntity part) {
        if (part.getLevel() instanceof ServerLevel level) reconcileOwned(level, part.getBlockPos());
    }

    private static void reconcileOwned(ServerLevel level, BlockPos pos) {
        var actual = owner(level.getBlockEntity(pos));
        var data = SuborAssemblyData.get(level);
        if (actual != null && data.changing.contains(actual.id())) return;
        var entry = actual == null ? null : data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || SuborConsoleBlock.compact(level.getBlockState(pos)) != entry.compact()
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // A stale proxy has no refundable authority. A lone anchor is kept
            // visible for inspection, with playback disabled; no ownership is guessed.
            if (level.getBlockEntity(pos) instanceof SuborPartBlockEntity)
                level.removeBlock(pos, false);
            return;
        }
        if (entry.closed()) { destroy(level, pos, null, false); return; }
        for (var cell : SuborFootprint.cells(entry.facing(), entry.compact())) {
            var other = position(anchor(entry), cell);
            if (!level.hasChunkAt(other)) continue; // Unloading is not dismantling.
            if (!owns(level, other, entry, cell.part())
                    || facing(level.getBlockState(other)) != entry.facing()
                    || (cell.part() == 0 && !SuborConsoleBlock.wide(level.getBlockState(other)))) {
                destroy(level, pos, null, true); return;
            }
        }
    }
}
