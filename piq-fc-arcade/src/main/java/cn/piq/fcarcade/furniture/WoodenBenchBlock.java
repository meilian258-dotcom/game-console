package cn.piq.fcarcade.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.*;
import java.util.List;

public final class WoodenBenchBlock extends FurnitureBlock {
    public enum Part implements StringRepresentable { LEFT,RIGHT;public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);} }
    public static final EnumProperty<Part> PART=EnumProperty.create("part",Part.class);
    public WoodenBenchBlock(WoodSpecies wood,Properties properties) { super(wood,properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(PART,Part.LEFT)); }
    @Override protected MapCodec<WoodenBenchBlock> codec() { return simpleCodec(p->new WoodenBenchBlock(wood(),p)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,PART); }
    @Override public boolean isBench() { return true; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { var s=super.getStateForPlacement(context);return FurnitureService.canPlace(context,s)?s:null; }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext context) { return (turns(s)%2==0)?Block.box(0,0,3.49762,16,8,12.50238):Block.box(3.49762,0,0,12.50238,8,16); }
    @Override protected List<ItemStack> getDrops(BlockState s,LootParams.Builder context) { return List.of(); }
    @Override public boolean onDestroyedByPlayer(BlockState state,Level level,BlockPos pos,Player player,boolean harvest,FluidState fluid) {
        return FurnitureService.breakBench(level,pos,state,player,harvest,fluid);
    }
}
