package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Independent one-block desktop CRT, using the original linked-TV lifecycle. */
public final class VintageTvBlock extends RetroTvBlock {
    public static final MapCodec<VintageTvBlock> CODEC=simpleCodec(VintageTvBlock::new);
    private static final VoxelShape[] SHAPES=createShapes();
    public VintageTvBlock(Properties properties){super(properties,ArcadeDisplayStyle.HOME_VINTAGE_TV);}
    @Override protected MapCodec<? extends RetroTvBlock> codec(){return CODEC;}
    @Override public boolean singleBlockTv(){return true;}
    private static VoxelShape[] createShapes(){
        var result=new VoxelShape[4];
        for(int i=0;i<4;i++){var b=VintageTvLayout.bounds(i);result[i]=Block.box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
        return result;
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return SHAPES[switch(state.getValue(FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;}];
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return getShape(state,level,pos,context);}
}
