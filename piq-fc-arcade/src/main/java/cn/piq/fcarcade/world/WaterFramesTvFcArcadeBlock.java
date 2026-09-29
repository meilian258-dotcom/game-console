package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class WaterFramesTvFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<WaterFramesTvFcArcadeBlock> CODEC =
            simpleCodec(WaterFramesTvFcArcadeBlock::new);

    public WaterFramesTvFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(
                properties,
                ArcadeMode.LOCKSTEP,
                ArcadeDisplayStyle.WATERFRAMES_TV);
    }

    @Override
    protected MapCodec<? extends WaterFramesTvFcArcadeBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return switch (state.getValue(FACING)) {
            case NORTH -> Block.box(-9, 0, 3, 25, 24, 7);
            case SOUTH -> Block.box(-9, 0, 9, 25, 24, 13);
            case WEST -> Block.box(3, 0, -9, 7, 24, 25);
            case EAST -> Block.box(9, 0, -9, 13, 24, 25);
            default -> Block.box(0, 0, 0, 16, 16, 16);
        };
    }
}
