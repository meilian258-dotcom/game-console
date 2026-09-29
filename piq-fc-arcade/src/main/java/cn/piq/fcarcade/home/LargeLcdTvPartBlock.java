package cn.piq.fcarcade.home;

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
public final class LargeLcdTvPartBlock extends Block implements EntityBlock {
    public static final MapCodec<LargeLcdTvPartBlock> CODEC = simpleCodec(LargeLcdTvPartBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 5);

    public LargeLcdTvPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FcArcadeBlock.FACING, Direction.NORTH)
                .setValue(PART, 1).setValue(RetroTvBlock.CENTERED, false));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FcArcadeBlock.FACING, PART, RetroTvBlock.CENTERED);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new LargeLcdTvPartBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlockEntities.LARGE_LCD_TV_PART.get()
                ? (world, pos, current, entity) -> ((LargeLcdTvPartBlockEntity) entity).maintenanceTick() : null;
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LargeLcdTvStructure.shape(state, state.getValue(PART));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LargeLcdTvStructure.collisionShape(state, state.getValue(PART));
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.LARGE_LCD_TV.get());
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        return HomeApplianceService.tryItemButton(stack,level,pos,player,hand,hit);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        return player instanceof ServerPlayer serverPlayer ? HomeHardware.interactTv(serverPlayer, pos, hit) : InteractionResult.SUCCESS;
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
                                                Player player, boolean willHarvest, FluidState fluid) {
        return LargeLcdTvStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        try {
            if (state.getBlock() != replacement.getBlock()) LargeLcdTvStructure.removed(level, pos);
        } finally { super.onRemove(state, level, pos, replacement, moved); }
    }
}
