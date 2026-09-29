package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** New one-cell CRTs. Retired small TVs remain registered and untouched. */
public final class UserTvBlock extends RetroTvBlock {
    public static final com.mojang.serialization.MapCodec<UserTvBlock> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), com.mojang.serialization.Codec.STRING.fieldOf("tv_style").xmap(ArcadeDisplayStyle::valueOf,ArcadeDisplayStyle::name).forGetter(UserTvBlock::displayStyle)
    ).apply(instance,UserTvBlock::new));
    public UserTvBlock(Properties properties, ArcadeDisplayStyle style){
        super(properties,style);
        if(style!=ArcadeDisplayStyle.HOME_GRAY_CRT&&style!=ArcadeDisplayStyle.HOME_RED_CRT)
            throw new IllegalArgumentException("Single-cell TV requires a CRT style");
    }
    @Override protected com.mojang.serialization.MapCodec<? extends RetroTvBlock> codec(){return CODEC;}
    @Override public boolean singleBlockTv(){return true;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        var b=UserTvLayout.bounds(displayStyle(),turns(state));
        // Antenna is decorative above the body: no invisible occupied cell above it.
        return box(b.minX(),b.minY(),b.minZ(),b.maxX(),Math.min(15.75,b.maxY()),b.maxZ());
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return getShape(state,level,pos,context);}
    static int turns(BlockState state){return switch(state.getValue(FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};}
}
