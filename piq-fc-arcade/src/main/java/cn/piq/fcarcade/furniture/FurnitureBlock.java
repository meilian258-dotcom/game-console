package cn.piq.fcarcade.furniture;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;

public abstract class FurnitureBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private final WoodSpecies wood;
    protected FurnitureBlock(WoodSpecies wood,Properties properties) { super(properties);this.wood=wood; }
    public WoodSpecies wood() { return wood; }
    public abstract boolean isBench();
    public static boolean isAnchor(BlockState state) { return !(state.getBlock() instanceof WoodenBenchBlock)||state.getValue(WoodenBenchBlock.PART)==WoodenBenchBlock.Part.LEFT; }
    public static int turns(BlockState state) { return switch(state.getValue(FACING)) { case EAST->1;case SOUTH->2;case WEST->3;default->0; }; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state) { return new FurnitureBlockEntity(pos,state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type) {
        return !level.isClientSide&&type==FurnitureRegistry.FURNITURE_ENTITY.get()?(l,p,s,e)->((FurnitureBlockEntity)e).maintenanceTick():null;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state,Mirror mirror) { return rotate(state,mirror.getRotation(state.getValue(FACING))); }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return InteractionResult.PASS;
        return player instanceof ServerPlayer server?FurnitureService.interact(server,pos,hit):InteractionResult.SUCCESS;
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving) {
        if(!state.is(next.getBlock()))FurnitureService.removed(level,pos,state);
        super.onRemove(state,level,pos,next,moving);
    }
}
