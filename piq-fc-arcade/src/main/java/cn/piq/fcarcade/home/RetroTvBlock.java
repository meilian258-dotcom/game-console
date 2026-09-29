package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.session.ArcadeMode;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

public class RetroTvBlock extends FcArcadeBlock implements EntityBlock {
    public static final MapCodec<RetroTvBlock> CODEC = simpleCodec(RetroTvBlock::new);
    public static final BooleanProperty CENTERED = BooleanProperty.create("centered");
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public RetroTvBlock(BlockBehaviour.Properties properties) {
        this(properties, ArcadeDisplayStyle.HOME_RETRO_TV);
    }
    protected RetroTvBlock(BlockBehaviour.Properties properties, ArcadeDisplayStyle style) {
        super(properties.lightLevel(state -> state.getValue(LIT) ? 10 : 0), ArcadeMode.LOCKSTEP, style);
        // Missing properties in an existing world must keep the old eight-cell layout.
        registerDefaultState(defaultBlockState().setValue(CENTERED, false).setValue(LIT, false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CENTERED, LIT);
    }
    @Override protected MapCodec<? extends RetroTvBlock> codec() { return CODEC; }
    @Override public boolean supportsFormation() { return false; }
    /** Single-cell LCD and knob CRT models opt out of the multi-cell assembly lifecycle. */
    public boolean singleBlockTv() { return false; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = super.getStateForPlacement(context);
        // New TVs use eight cells; existing centred states keep their persisted layout.
        if (state != null) state = state.setValue(CENTERED, false);
        if (singleBlockTv()) return state;
        return state != null && HomeTvStructure.canPlace(context, state) ? state : null;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new HomeTvBlockEntity(pos, state); }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        HomeTvStructure.confirmPlacement(level, pos);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlockEntities.HOME_TV.get()
                ? (world, pos, current, entity) -> ((HomeTvBlockEntity) entity).maintenanceTick() : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) return HomeHardware.interactTv(serverPlayer, pos, hit);
        return InteractionResult.SUCCESS;
    }

    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        return HomeApplianceService.tryItemButton(stack,level,pos,player,hand,hit);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        try {
            if (state.getBlock() != replacement.getBlock()) HomeTvStructure.removed(level, pos, state);
        } finally { super.onRemove(state, level, pos, replacement, moved); }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HomeTvStructure.shape(state, 0);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HomeTvStructure.collisionShape(state, 0);
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
                                                Player player, boolean willHarvest, FluidState fluid) {
        if (singleBlockTv()) return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        return HomeTvStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid);
    }

    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder context) {
        if (singleBlockTv()) return super.getDrops(state, context);
        // The assembly's first removal owns the one television drop, including
        // breaking a proxy or several explosion hits. Never also emit anchor loot.
        return List.of();
    }
}
