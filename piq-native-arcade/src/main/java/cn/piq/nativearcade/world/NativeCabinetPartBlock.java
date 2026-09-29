// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import cn.piq.nativearcade.world.NativeCabinetBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Invisible physical proxy. No item registration and no loot of its own. */
public final class NativeCabinetPartBlock extends Block implements EntityBlock {
    public static final MapCodec<NativeCabinetPartBlock> CODEC = simpleCodec(NativeCabinetPartBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part", 1, 5);

    public NativeCabinetPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NativeCabinetBlock.FACING, Direction.NORTH)
                .setValue(PART, 1));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NativeCabinetBlock.FACING, PART);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NativeCabinetPartBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == NativeArcadeRegistries.PART_ENTITY.get()
                ? (world, pos, current, entity) -> ((NativeCabinetPartBlockEntity) entity).maintenanceTick() : null;
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return NativeCabinetStructure.shape(state, state.getValue(PART));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return NativeCabinetStructure.collisionShape(state, state.getValue(PART));
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(NativeArcadeRegistries.CABINET_ITEM.get());
    }
    @Override protected java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder context) { return java.util.List.of(); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        return player instanceof ServerPlayer serverPlayer ? NativeCabinetStructure.interact(serverPlayer, pos, hit) : InteractionResult.SUCCESS;
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
                                                Player player, boolean willHarvest, FluidState fluid) {
        return NativeCabinetStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        try {
            if (state.getBlock() != replacement.getBlock()) NativeCabinetStructure.removed(level, pos);
        } finally { super.onRemove(state, level, pos, replacement, moved); }
    }
}

