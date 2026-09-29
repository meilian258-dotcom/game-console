package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class WaterFramesPanelFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<WaterFramesPanelFcArcadeBlock> CODEC =
            simpleCodec(WaterFramesPanelFcArcadeBlock::new);

    public WaterFramesPanelFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(
                properties,
                ArcadeMode.LOCKSTEP,
                ArcadeDisplayStyle.WATERFRAMES_PANEL);
    }

    @Override
    protected MapCodec<? extends WaterFramesPanelFcArcadeBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        if (!facing.getAxis().isHorizontal()) return null;
        BlockState state = defaultBlockState().setValue(FACING, facing);
        return state.canSurvive(context.getLevel(), context.getClickedPos())
                ? state
                : null;
    }

    @Override
    protected boolean canSurvive(
            BlockState state,
            LevelReader level,
            BlockPos pos
    ) {
        Direction facing = state.getValue(FACING);
        BlockPos supportPos = pos.relative(facing.getOpposite());
        return level.getBlockState(supportPos).isFaceSturdy(
                level,
                supportPos,
                facing);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        if (direction == state.getValue(FACING).getOpposite()
                && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(
                state,
                direction,
                neighborState,
                level,
                pos,
                neighborPos);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return switch (state.getValue(FACING)) {
            case NORTH -> Block.box(0, 0, 15.5, 16, 16, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, 16, 0.5);
            case WEST -> Block.box(15.5, 0, 0, 16, 16, 16);
            case EAST -> Block.box(0, 0, 0, 0.5, 16, 16);
            default -> Block.box(0, 0, 0, 16, 16, 16);
        };
    }
}
