package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One physical block; keeps the same AV and simulation endpoint type as the CRT. */
public final class LcdTvBlock extends RetroTvBlock {
    public static final MapCodec<LcdTvBlock> CODEC = simpleCodec(LcdTvBlock::new);
    private static final VoxelShape[] SHAPES = createShapes();
    public LcdTvBlock(Properties properties) { super(properties, ArcadeDisplayStyle.HOME_LCD_TV); }
    @Override protected MapCodec<? extends RetroTvBlock> codec() { return CODEC; }
    @Override public boolean singleBlockTv() { return true; }
    private static VoxelShape[] createShapes() {
        var shapes = new VoxelShape[4];
        for (int turns = 0; turns < shapes.length; turns++) {
            var b = LcdTvLayout.bounds(turns);
            shapes[turns] = Block.box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        }
        return shapes;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[switch (state.getValue(FACING)) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; }];
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
