package cn.piq.fcarcade.world;

import cn.piq.fcarcade.session.ArcadeMode;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class WaterFramesFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<WaterFramesFcArcadeBlock> CODEC =
            simpleCodec(WaterFramesFcArcadeBlock::new);

    public WaterFramesFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(
                properties,
                ArcadeMode.LOCKSTEP,
                ArcadeDisplayStyle.WATERFRAMES_BIG_TV);
    }

    @Override
    protected MapCodec<? extends WaterFramesFcArcadeBlock> codec() {
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
            case NORTH -> Block.box(-14, 0, 2, 30, 32, 5);
            case SOUTH -> Block.box(-14, 0, 11, 30, 32, 14);
            case WEST -> Block.box(2, 0, -14, 5, 32, 30);
            case EAST -> Block.box(11, 0, -14, 14, 32, 30);
            default -> Block.box(0, 0, 0, 16, 16, 16);
        };
    }
}
