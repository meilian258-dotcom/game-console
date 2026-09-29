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

/** Independent two-cell LCD lifecycle. Never reads or mutates the CRT assembly ledger. */
public final class WideLcdTvStructure {
    private WideLcdTvStructure() {}

    static boolean singleBlock(BlockState state) {
        return false;
    }

    public static boolean centered(BlockState state) {
        return false;
    }

    private static boolean validPart(BlockState state, int part) {
        return state.getBlock() instanceof WideLcdTvPartBlock ? part == 1
                : state.getBlock() instanceof WideLcdTvBlock && part == 0;
    }

    static WideLcdTvFootprint.Facing facing(BlockState state) {
        return WideLcdTvFootprint.Facing.valueOf(state.getValue(FcArcadeBlock.FACING).name());
    }

    static BlockPos position(BlockPos anchor, WideLcdTvFootprint.Cell cell) {
        return anchor.offset(cell.x(), cell.y(), cell.z());
    }

    static BlockPos anchor(WideLcdTvAssemblyLedger.Assembly assembly) {
        return new BlockPos(assembly.x(), assembly.y(), assembly.z());
    }

    static VoxelShape shape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = WideLcdTvFootprint.selection(facing(state), part, centered(state));
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static VoxelShape collisionShape(BlockState state, int part) {
        if (!validPart(state, part)) return Shapes.empty();
        var box = WideLcdTvFootprint.clipped(facing(state), part, centered(state));
        return Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    static BlockState partState(BlockState anchor, int part) {
        return ModBlocks.WIDE_LCD_TV_PART.get().defaultBlockState()
                .setValue(FcArcadeBlock.FACING, anchor.getValue(FcArcadeBlock.FACING))
                .setValue(WideLcdTvPartBlock.PART, part)
                .setValue(RetroTvBlock.CENTERED, centered(anchor));
    }

    /** Resolves loaded proxy clicks only after its BE and physical anchor agree. */
    public static BlockPos resolveAnchor(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (entity instanceof HomeTvBlockEntity && level.getBlockState(pos).getBlock() instanceof WideLcdTvBlock) return pos;
        if (!(entity instanceof WideLcdTvPartBlockEntity part) || part.owner() == null || part.anchor() == null
                || !level.hasChunkAt(part.anchor())) return null;
        if (!(level.getBlockEntity(part.anchor()) instanceof HomeTvBlockEntity tv)
                || !(tv.getBlockState().getBlock() instanceof WideLcdTvBlock)
                || !tv.structureInstalled() || !part.owner().equals(tv.hardwareId())) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WideLcdTvPartBlock) || facing(state) != facing(tv.getBlockState())
                || centered(state) != centered(tv.getBlockState())
                || !validPart(state, state.getValue(WideLcdTvPartBlock.PART))) return null;
        return position(part.anchor(), WideLcdTvFootprint.cell(facing(state), state.getValue(WideLcdTvPartBlock.PART), centered(state))).equals(pos)
                ? part.anchor() : null;
    }

    public static boolean complete(Level level, BlockPos anchorPos) {
        if (!level.hasChunkAt(anchorPos) || !(level.getBlockEntity(anchorPos) instanceof HomeTvBlockEntity tv)
                || !(tv.getBlockState().getBlock() instanceof WideLcdTvBlock)) return false;
        if (singleBlock(tv.getBlockState())) return !tv.isRemoved();
        if (!tv.structureInstalled()) return false;
        var facing = facing(tv.getBlockState());
        boolean centered = centered(tv.getBlockState());
        if (level instanceof ServerLevel server) {
            var data = WideLcdTvAssemblyData.get(server);
            if (data.ledger.pending(tv.hardwareId())) return false;
            var entry = data.ledger.get(tv.hardwareId());
            if (entry == null || entry.closed() || !anchor(entry).equals(anchorPos) || entry.facing() != facing
                    || entry.centered() != centered) return false;
        }
        for (var cell : WideLcdTvFootprint.cells(facing, centered)) {
            var pos = position(anchorPos, cell);
            if (!level.hasChunkAt(pos)) return false;
            if (cell.part() == 0) continue;
            var state = level.getBlockState(pos);
            if (!(level.getBlockEntity(pos) instanceof WideLcdTvPartBlockEntity part)
                    || !tv.hardwareId().equals(part.owner()) || !anchorPos.equals(part.anchor())
                    || !(state.getBlock() instanceof WideLcdTvPartBlock)
                    || state.getValue(WideLcdTvPartBlock.PART) != cell.part() || facing(state) != facing
                    || centered(state) != centered) return false;
        }
        return true;
    }

    static boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!(state.getBlock() instanceof WideLcdTvBlock)) return false;
        var level = context.getLevel();
        var player = context.getPlayer();
        var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        return WideLcdTvFootprint.canPlace(facing(state), centered(state), cell -> {
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
        var data = WideLcdTvAssemblyData.get(server);
        var placed = new ArrayList<Placed>();
        UUID id = null;
        boolean committed = false;
        try {
            HomeTvBlockEntity tv = null;
            for (var cell : WideLcdTvFootprint.cells(facing(state), centered(state))) {
                var pos = position(anchorPos, cell);
                var installed = cell.part() == 0 ? state : partState(state, cell.part());
                var before = level.getBlockState(pos);
                // Repeat immediately before each write; hooks must not let a
                // different block appear after preflight and then be overwritten.
                var cellContext = BlockPlaceContext.at(context, pos, context.getClickedFace());
                if (!cellContext.getClickedPos().equals(pos) || !before.canBeReplaced(cellContext)
                        || level.getBlockEntity(pos) != null) return false;
                if (!level.setBlock(pos, installed, Block.UPDATE_CLIENTS)) return false;
                var entity = level.getBlockEntity(pos);
                placed.add(new Placed(pos, before, installed, entity));
                if (cell.part() == 0) {
                    if (!(entity instanceof HomeTvBlockEntity created)) return false;
                    tv = created; id = tv.hardwareId(); data.changing.add(id);
                } else {
                    if (!(entity instanceof WideLcdTvPartBlockEntity part)) return false;
                    part.attach(id, anchorPos);
                }
            }
            if (tv == null || !data.ledger.restore(new WideLcdTvAssemblyLedger.Assembly(id,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(), facing(state), false, 0, centered(state)))) return false;
            if (level.captureBlockSnapshots) data.ledger.awaitPlacementEvent(id);
            tv.installStructure(); data.setDirty(); committed = true;
            if (!level.captureBlockSnapshots)
                for (var placedCell : placed) level.blockUpdated(placedCell.pos(), placedCell.installed().getBlock());
            return true;
        } finally {
            if (!committed) {
                // Only this exact just-created BE/state may be restored. No drops,
                // no displacement of a replacement introduced by another hook.
                for (int i = placed.size() - 1; i >= 0; i--) {
                    var cell = placed.get(i);
                    if (level.getBlockState(cell.pos()) == cell.installed()
                            && level.getBlockEntity(cell.pos()) == cell.entity())
                        level.setBlock(cell.pos(), cell.before(), Block.UPDATE_ALL);
                }
            }
            if (id != null) data.changing.remove(id);
        }
    }

    private record Owner(UUID id, BlockPos anchor, int part) {}

    private static Owner owner(BlockEntity entity) {
        if (entity instanceof HomeTvBlockEntity tv && tv.getBlockState().getBlock() instanceof WideLcdTvBlock)
            return new Owner(tv.hardwareId(), tv.getBlockPos(), 0);
        if (entity instanceof WideLcdTvPartBlockEntity part && part.owner() != null && part.anchor() != null
                && part.getBlockState().getBlock() instanceof WideLcdTvPartBlock
                && validPart(part.getBlockState(), part.getBlockState().getValue(WideLcdTvPartBlock.PART)))
            return new Owner(part.owner(), part.anchor(), part.getBlockState().getValue(WideLcdTvPartBlock.PART));
        return null;
    }

    private static boolean owns(Level level, BlockPos pos, WideLcdTvAssemblyLedger.Assembly assembly, int part) {
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
            var assembly = WideLcdTvAssemblyData.get(server).ledger.get(actual.id());
            if (assembly != null && actual.anchor().equals(anchor(assembly))
                    && assembly.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
                var result = WideLcdTvRemovalGate.attempt(assembly.facing(), actual.part(), assembly.centered(),
                        cell -> level.hasChunkAt(position(actual.anchor(), cell)),
                        cell -> level.mayInteract(player, position(actual.anchor(), cell)),
                        cell -> owns(level, position(actual.anchor(), cell), assembly, cell.part()),
                        cell -> !(player instanceof ServerPlayer serverPlayer) || !CommonHooks.fireBlockBreak(level,
                                serverPlayer.gameMode.getGameModeForPlayer(), serverPlayer,
                                position(actual.anchor(), cell), level.getBlockState(position(actual.anchor(), cell))).isCanceled(),
                        () -> level.hasChunkAt(pos) && level.getBlockState(pos) == state
                                && level.getBlockEntity(pos) == originalEntity, commit);
                if (result == WideLcdTvRemovalGate.Result.REMOVED) return true;
                return denyBreak(server, pos, player, result == WideLcdTvRemovalGate.Result.DENIED ? "denied" : "tv_incomplete");
            }
        }
        if (level.getBlockState(pos) != state || level.getBlockEntity(pos) != originalEntity)
            return denyBreak(server, pos, player, "tv_incomplete");
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
        removed(level, pos, level.getBlockState(pos));
    }

    static void removed(Level level, BlockPos pos, BlockState previous) {
        if (!(level instanceof ServerLevel server)) return;
        if (singleBlock(previous)) {
            if (!level.restoringBlockSnapshots) HomeHardware.removed(level, pos);
            return;
        }
        if (level.restoringBlockSnapshots) {
            // NeoForge cancelled EntityMultiPlaceEvent and is restoring all
            // placement snapshots itself. Never recurse into world cleanup or emit
            // drops/refunds here; the held TV item has already been restored.
            var actual = owner(level.getBlockEntity(pos));
            var data = WideLcdTvAssemblyData.get(server);
            if (actual != null && data.ledger.cancelPlacement(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ()))
                data.setDirty();
            return;
        }
        destroy(server, pos, pos, true);
    }

    static void confirmPlacement(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        if (level.getBlockEntity(pos) instanceof HomeTvBlockEntity tv && !singleBlock(tv.getBlockState())) {
            WideLcdTvAssemblyData.get(server).ledger.confirmPlacement(tv.hardwareId());
        }
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockPos alreadyRemoving, boolean drop) {
        var entity = level.getBlockEntity(pos);
        var actual = owner(entity);
        if (actual == null) return;
        var data = WideLcdTvAssemblyData.get(level);
        if (data.changing.contains(actual.id())) return;
        if (data.ledger.pending(actual.id())) return;
        var entry = data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // An unmanaged anchor created by an operator remains inspectable until removed.
            if (entity instanceof HomeTvBlockEntity tv && tv.claimStandaloneRemoval()) {
                HomeHardware.removed(level, pos);
                if (alreadyRemoving == null && level.getBlockEntity(pos) == tv) level.removeBlock(pos, false);
                // A managed orphan with no ledger may be an incomplete snapshot
                // rollback (held item already returned); never mint another TV.
                if (drop && !tv.structureInstalled()) Block.popResource(level, pos, new ItemStack(ModItems.WIDE_LCD_TV.get()));
            }
            return;
        }
        boolean first = data.ledger.close(actual.id(), actual.part(), pos.getX(), pos.getY(), pos.getZ());
        data.setDirty(); // Claim once before either TV or AV cable is emitted.
        data.changing.add(actual.id());
        try {
            HomeHardware.removedTvIdentity(level, actual.anchor(), actual.id(), pos);
            cleanupLoaded(level, data, data.ledger.get(actual.id()), alreadyRemoving);
            if (first && drop) Block.popResource(level, pos, new ItemStack(ModItems.WIDE_LCD_TV.get()));
        } finally { data.changing.remove(actual.id()); }
    }

    private static void cleanupLoaded(ServerLevel level, WideLcdTvAssemblyData data,
                                      WideLcdTvAssemblyLedger.Assembly entry, BlockPos alreadyRemoving) {
        if (entry == null || !entry.closed()) return;
        for (var cell : WideLcdTvFootprint.cells(entry.facing(), entry.centered())) {
            var pos = position(anchor(entry), cell);
            if (!level.hasChunkAt(pos)) continue;
            if (owns(level, pos, entry, cell.part()) && !pos.equals(alreadyRemoving)) {
                var original = level.getBlockEntity(pos);
                if (cell.part() == 0) HomeHardware.removed(level, pos);
                if (level.getBlockEntity(pos) == original)
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            // A loaded foreign replacement is acknowledged, never deleted.
            data.ledger.acknowledge(entry.id(), cell.part()); data.setDirty();
        }
    }

    static void reconcile(HomeTvBlockEntity tv) {
        if (!(tv.getLevel() instanceof ServerLevel level) || singleBlock(tv.getBlockState()) || !tv.structureInstalled()) return;
        confirmPlacement(level, tv.getBlockPos());
        reconcileOwned(level, tv.getBlockPos());
    }

    static void reconcile(WideLcdTvPartBlockEntity part) {
        if (part.getLevel() instanceof ServerLevel level) reconcileOwned(level, part.getBlockPos());
    }

    private static void reconcileOwned(ServerLevel level, BlockPos pos) {
        var actual = owner(level.getBlockEntity(pos));
        var data = WideLcdTvAssemblyData.get(level);
        if (actual != null && data.changing.contains(actual.id())) return;
        var entry = actual == null ? null : data.ledger.get(actual.id());
        if (entry == null || !actual.anchor().equals(anchor(entry))
                || !entry.owns(actual.part(), pos.getX(), pos.getY(), pos.getZ())) {
            // A stale proxy has no refundable authority. A lone anchor is kept
            // visible for inspection, with playback disabled; no ownership is guessed.
            if (level.getBlockEntity(pos) instanceof WideLcdTvPartBlockEntity)
                level.removeBlock(pos, false);
            return;
        }
        if (entry.closed()) { destroy(level, pos, null, false); return; }
        for (var cell : WideLcdTvFootprint.cells(entry.facing(), entry.centered())) {
            var other = position(anchor(entry), cell);
            if (!level.hasChunkAt(other)) continue; // Unloading is not dismantling.
            if (!owns(level, other, entry, cell.part())
                    || facing(level.getBlockState(other)) != entry.facing()
                    || centered(level.getBlockState(other)) != entry.centered()) {
                destroy(level, pos, null, true); return;
            }
        }
    }
}
