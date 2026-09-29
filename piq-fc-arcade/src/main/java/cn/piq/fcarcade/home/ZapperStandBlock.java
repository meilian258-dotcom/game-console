package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ZapperStandGeometry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class ZapperStandBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<ZapperStandBlock> CODEC=simpleCodec(ZapperStandBlock::new);
    public ZapperStandBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return rotate(s,m.getRotation(s.getValue(FACING)));}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new ZapperStandBlockEntity(p,s);}
    public static int turns(BlockState s){return switch(s.getValue(FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){var b=ZapperStandGeometry.bounds(turns(s));return Shapes.box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState s,Level l,BlockPos p,Player player,InteractionHand h,BlockHitResult hit){
        if(stack.getItem() instanceof ZapperStandCableItem)return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if(h!=InteractionHand.MAIN_HAND||!(stack.getItem() instanceof HomeZapperItem))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(player instanceof ServerPlayer server)ZapperStandService.interact(server,p,hit);return ItemInteractionResult.sidedSuccess(l.isClientSide);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(player instanceof ServerPlayer server)ZapperStandService.interact(server,p,hit);return InteractionResult.sidedSuccess(l.isClientSide);}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moving){
        if(!s.is(next.getBlock())&&l.getBlockEntity(p) instanceof ZapperStandBlockEntity stand)ZapperStandService.removed(stand);
        super.onRemove(s,l,p,next,moving);}
}
