package cn.piq.sfchome.world;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import cn.piq.sfchome.server.SfcHomeServer;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
public final class SfcHomeConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<SfcHomeConsoleBlock> CODEC=simpleCodec(SfcHomeConsoleBlock::new);
    public SfcHomeConsoleBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    @Override public RenderShape getRenderShape(BlockState state){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING);}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return rotate(s,m.getRotation(s.getValue(FACING)));}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        int turns=switch(s.getValue(FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
        VoxelShape shape=box(cn.piq.sfchome.layout.SfcConsoleScale.body(turns));
        // Fixed conservative outline/collision: safe for vanilla's per-state shape cache.
        for(int port=0;port<2;port++)shape=Shapes.or(shape,box(cn.piq.sfchome.layout.SfcConsoleScale.pad(port,turns)));
        shape=Shapes.or(shape,box(cn.piq.sfchome.layout.SfcConsoleScale.inserted(turns)));
        return shape;
    }
    private static VoxelShape box(cn.piq.sfchome.layout.SfcConsoleScale.Bounds b){return Block.box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new SfcHomeConsoleBlockEntity(p,s);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){return !l.isClientSide&&t==SfcHomeRegistries.CONSOLE_ENTITY.get()?(w,p,b,e)->((SfcHomeConsoleBlockEntity)e).maintenanceTick():null;}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        // Vanilla also reaches the default block interaction while an item is held.
        // Let AV cables (and all other unrelated items) reach Item.useOn instead
        // of swallowing them as an empty-hand console/controller interaction.
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return InteractionResult.PASS;
        if(player instanceof ServerPlayer server)SfcHomeServer.interactDirect(server,InteractionHand.MAIN_HAND,p,hit);
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack item,BlockState s,Level l,BlockPos p,Player player,InteractionHand hand,BlockHitResult hit){
        if(!cn.piq.sfchome.data.SfcCartridgeData.isCartridge(item)||player.isShiftKeyDown()){
            if(!cn.piq.sfchome.data.SfcCartridgeData.isCartridge(item)&&!connectionTool(item)&&player instanceof ServerPlayer server){var button=cn.piq.fcarcade.home.HomeApplianceService.tryButton(server,p,hand,hit);if(button.consumesAction())return ItemInteractionResult.SUCCESS;if(button==InteractionResult.FAIL)return ItemInteractionResult.FAIL;}
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if(player instanceof ServerPlayer server)SfcHomeServer.interactDirect(server,hand,p,hit);return ItemInteractionResult.sidedSuccess(l.isClientSide);
    }
    private static boolean connectionTool(ItemStack item){return item.getItem() instanceof cn.piq.fcarcade.home.AvCableItem||item.getItem() instanceof cn.piq.fcarcade.cabinet.CabinetLinkCableItem||item.getItem() instanceof cn.piq.fcarcade.home.ZapperStandCableItem||item.getItem() instanceof cn.piq.fcarcade.home.HomeZapperItem;}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moving){try{if(s.getBlock()!=next.getBlock())HomeHardware.removed(l,p);}finally{super.onRemove(s,l,p,next,moving);}}
}
