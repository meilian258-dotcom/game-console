package cn.piq.fcarcade.world;

import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeHardwareScale;
import cn.piq.fcarcade.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A detailed, floor- or table-placeable red-and-cream 8-bit console model.
 *
 * <p>The original block ID/model remain; cartridge and AV hardware are stored
 * in its block entity. A linked television supplies the session anchor.</p>
 */
public class FamicomConsoleBlock extends Block implements EntityBlock {
    public static final MapCodec<FamicomConsoleBlock> CODEC =
            simpleCodec(FamicomConsoleBlock::new);
    public static final DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private static final VoxelShape NORTH_SHAPE =
            scaledBox(-1.12, 0.18, -3.51, 17.12, 11.90001, 20.24082);
    private static final VoxelShape SOUTH_SHAPE =
            scaledBox(-1.12, 0.18, -4.24082, 17.12, 11.90001, 19.51);
    private static final VoxelShape WEST_SHAPE =
            scaledBox(-3.51, 0.18, -1.12, 20.24082, 11.90001, 17.12);
    private static final VoxelShape EAST_SHAPE =
            scaledBox(-4.24082, 0.18, -1.12, 19.51, 11.90001, 17.12);

    private static VoxelShape scaledBox(double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        var min = HomeHardwareScale.consolePoint(minX, minY, minZ);
        var max = HomeHardwareScale.consolePoint(maxX, maxY, maxZ);
        return Block.box(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    public FamicomConsoleBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(
                FACING,
                Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HomeConsoleBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlockEntities.HOME_CONSOLE.get()
                ? (world, pos, current, entity) -> ((HomeConsoleBlockEntity) entity).maintenanceTick() : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) return HomeHardware.interactConsole(serverPlayer, pos, hit);
        return InteractionResult.SUCCESS;
    }

    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        return cn.piq.fcarcade.home.HomeApplianceService.tryItemButton(stack,level,pos,player,hand,hit);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState replacement, boolean movedByPiston) {
        try {
            if (state.getBlock() != replacement.getBlock()) removeHardware(state, level, pos);
        } finally { super.onRemove(state, level, pos, replacement, movedByPiston); }
    }

    /** Variants may own a multi-cell removal; the original FC still cleans up exactly once here. */
    protected void removeHardware(BlockState state, Level level, BlockPos pos) {
        HomeHardware.removed(level, pos);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(
                FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder
    ) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            net.minecraft.core.BlockPos pos,
            CollisionContext context
    ) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }
}
