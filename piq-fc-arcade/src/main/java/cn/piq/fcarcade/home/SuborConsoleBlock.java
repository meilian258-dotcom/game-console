package cn.piq.fcarcade.home;

import cn.piq.fcarcade.world.FamicomConsoleBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

/** Legacy single/four-cell and newly placed two-cell SB926 retain their own saved footprint. */
public final class SuborConsoleBlock extends FamicomConsoleBlock {
    public static final MapCodec<SuborConsoleBlock> CODEC = simpleCodec(SuborConsoleBlock::new);
    public static final BooleanProperty WIDE = BooleanProperty.create("wide");
    public static final BooleanProperty COMPACT = BooleanProperty.create("compact");
    private static final VoxelShape[] SHAPES = createShapes();

    public SuborConsoleBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WIDE, false).setValue(COMPACT, false));
    }

    public static boolean wide(BlockState state) { return state.hasProperty(WIDE) && state.getValue(WIDE); }
    public static boolean compact(BlockState state) { return state.hasProperty(COMPACT) && state.getValue(COMPACT); }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(WIDE, COMPACT);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = super.getStateForPlacement(context);
        if (state != null) state = state.setValue(WIDE, true).setValue(COMPACT, true);
        return state != null && SuborStructure.canPlace(context, state) ? state : null;
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (wide(state)) SuborStructure.confirmPlacement(level, pos);
    }

    @Override protected void removeHardware(BlockState state, Level level, BlockPos pos) {
        if (wide(state)) SuborStructure.removed(level, pos);
        else super.removeHardware(state, level, pos);
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
                                                Player player, boolean willHarvest, FluidState fluid) {
        return wide(state) ? SuborStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid)
                : super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder context) {
        return wide(state) ? List.of() : super.getDrops(state, context);
    }

    private static VoxelShape[] createShapes() {
        VoxelShape[] shapes = new VoxelShape[4];
        for (int turns = 0; turns < shapes.length; turns++) {
            var bounds = HomeConsoleLayout.suborBounds(turns);
            shapes[turns] = Block.box(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());
        }
        return shapes;
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    // The free mesh is drawn once by the shared console BER, never as a static duplicate.
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                             CollisionContext context) {
        if (wide(state)) return SuborStructure.shape(state, 0);
        int turns = switch (state.getValue(FACING)) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        return SHAPES[turns];
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                                      CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
