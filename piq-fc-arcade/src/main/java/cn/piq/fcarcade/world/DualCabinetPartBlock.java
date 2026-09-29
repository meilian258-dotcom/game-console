package cn.piq.fcarcade.world;

import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.world.FcArcadeBlock;
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
public final class DualCabinetPartBlock extends Block implements EntityBlock {
    public static final MapCodec<DualCabinetPartBlock> CODEC = simpleCodec(DualCabinetPartBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part", 1, 11);

    public DualCabinetPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FcArcadeBlock.FACING, Direction.NORTH)
                .setValue(PART, 1).setValue(DualCabinetBlock.COMPACT, false));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FcArcadeBlock.FACING, PART, DualCabinetBlock.COMPACT);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DualCabinetPartBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlockEntities.DUAL_CABINET_PART.get()
                ? (world, pos, current, entity) -> ((DualCabinetPartBlockEntity) entity).maintenanceTick() : null;
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DualCabinetStructure.shape(state, state.getValue(PART));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DualCabinetStructure.collisionShape(state, state.getValue(PART));
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.DUAL_CABINET.get());
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        if(player.getMainHandItem().getItem() instanceof cn.piq.fcarcade.home.ZapperStandCableItem)return InteractionResult.PASS;
        return player instanceof ServerPlayer serverPlayer ? DualCabinetStructure.interact(serverPlayer, pos, hit) : InteractionResult.SUCCESS;
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
                                                Player player, boolean willHarvest, FluidState fluid) {
        return DualCabinetStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        try {
            if (state.getBlock() != replacement.getBlock()) DualCabinetStructure.removed(level, pos, state);
        } finally { super.onRemove(state, level, pos, replacement, moved); }
    }
}
