package cn.piq.fcarcade.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.*;

public final class FoldingStoolBlock extends FurnitureBlock {
    public static final BooleanProperty FOLDED=BooleanProperty.create("folded");
    public FoldingStoolBlock(WoodSpecies wood,Properties properties) { super(wood,properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(FOLDED,false)); }
    @Override protected MapCodec<FoldingStoolBlock> codec() { return simpleCodec(p->new FoldingStoolBlock(wood(),p)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,FOLDED); }
    @Override public boolean isBench() { return false; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state=super.getStateForPlacement(context);var components=context.getItemInHand().get(DataComponents.BLOCK_STATE);
        return components!=null&&Boolean.TRUE.equals(components.get(FOLDED))?state.setValue(FOLDED,true):state;
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext context) {
        if(!s.getValue(FOLDED))return turns(s)%2==0?Block.box(3.265,0,4.05295,12.735,6.1513896,11.94705):Block.box(4.05295,0,3.265,11.94705,6.1513896,12.735);
        return turns(s)%2==0?Block.box(3.265,0,6.8,12.735,9.4325,9.2):Block.box(6.8,0,3.265,9.2,9.4325,12.735);
    }
}
