package cn.piq.computer.world;

import cn.piq.computer.*;
import cn.piq.fcarcade.home.HomeHardware;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.CommonHooks;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

public final class ComputerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<ComputerBlock> CODEC=simpleCodec(ComputerBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF=BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final ThreadLocal<Boolean> BREAK_CHECK=ThreadLocal.withInitial(()->false);
    public ComputerBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(HALF,DoubleBlockHalf.LOWER));}
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,HALF);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        if(s.getValue(HALF)==DoubleBlockHalf.UPPER)return Shapes.empty();
        var a=ComputerGeometry.units(2.4,0,.6);var b=ComputerGeometry.units(13.4,22,15.4);
        return s.getValue(FACING).getAxis()==Direction.Axis.Z?Shapes.create(a.x,a.y,a.z,b.x,b.y,b.z):Shapes.create(a.z,a.y,a.x,b.z,b.y,b.x);
    }
    public static BlockPos anchor(BlockPos p,BlockState s){return s.getValue(HALF)==DoubleBlockHalf.UPPER?p.below():p;}
    public static ComputerEntity find(Level l,BlockPos p){if(!l.hasChunkAt(p))return null;var s=l.getBlockState(p);if(!(s.getBlock() instanceof ComputerBlock))return null;return l.getBlockEntity(anchor(p,s)) instanceof ComputerEntity pc?pc:null;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return s.getValue(HALF)==DoubleBlockHalf.LOWER?new ComputerEntity(p,s):null;}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){return !l.isClientSide&&t==ComputerRegistry.COMPUTER.get()?(world,p,state,entity)->((ComputerEntity)entity).serverTick():null;}
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState s,Level l,BlockPos pos,Player p,InteractionHand h,BlockHitResult hit){
        if(h!=InteractionHand.MAIN_HAND)return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        for(var part:Assembly.Part.values())if(stack.is(ComputerRegistry.item(part))){if(p instanceof ServerPlayer sp){var pc=find(l,pos);if(pc!=null&&ComputerAccess.near(sp,pos)&&ComputerAccess.allowed(sp,pc.getBlockPos())&&find(l,pos)==pc)pc.install(sp,stack,part);}return ItemInteractionResult.sidedSuccess(l.isClientSide);}
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos pos,Player player,BlockHitResult hit){
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return InteractionResult.PASS;
        if(player instanceof ServerPlayer p){var pc=find(l,pos);if(pc!=null&&ComputerAccess.near(p,pos)&&ComputerAccess.allowed(p,pc.getBlockPos())&&find(l,pos)==pc){
            if(p.isShiftKeyDown())pc.togglePanel(p);
            else if(powerHit(p,pc))pc.togglePower(p);
            else pc.openAssembly(p);
        }}return InteractionResult.sidedSuccess(l.isClientSide);
    }
    /** Ray-test the physical front button, not the entire front face; transform with the model. */
    private static boolean powerHit(ServerPlayer p,ComputerEntity pc){
        var start=ComputerGeometry.unscaled(local(p.getEyePosition().subtract(Vec3.atLowerCornerOf(pc.getBlockPos())),pc.getBlockState().getValue(FACING)));
        var end=ComputerGeometry.unscaled(local(p.getEyePosition().add(p.getLookAngle().scale(Math.min(6,p.blockInteractionRange()))).subtract(Vec3.atLowerCornerOf(pc.getBlockPos())),pc.getBlockState().getValue(FACING)));
        return start.z<.08&&new AABB(9.9/16,13.45/16,.55/16,11.6/16,15.15/16,1.3/16).clip(start,end).isPresent();
    }
    public static Vec3 local(Vec3 v,Direction d){return switch(d){case EAST->new Vec3(v.z,v.y,1-v.x);case SOUTH->new Vec3(1-v.x,v.y,1-v.z);case WEST->new Vec3(1-v.z,v.y,v.x);default->v;};}
    @Override public boolean onDestroyedByPlayer(BlockState state,Level l,BlockPos pos,Player player,boolean harvest,FluidState fluid){
        if(l.isClientSide)return l.setBlock(pos,fluid.createLegacyBlock(),11);
        if(BREAK_CHECK.get()||!(player instanceof ServerPlayer p))return false;
        var mate=state.getValue(HALF)==DoubleBlockHalf.UPPER?pos.below():pos.above();var pc=find(l,pos);
        if(pc==null||!l.mayInteract(player,pos))return false;
        var previous=l.getBlockState(mate);
        if(previous.is(this)&&previous.getValue(HALF)!=state.getValue(HALF)){
            if(!l.mayInteract(player,mate))return false;
            BREAK_CHECK.set(true);try{if(CommonHooks.fireBlockBreak(l,p.gameMode.getGameModeForPlayer(),p,mate,previous).isCanceled())return false;}finally{BREAK_CHECK.remove();}
        }
        if(find(l,pos)!=pc||l.getBlockState(pos)!=state||l.getBlockState(mate)!=previous)return false;
        pc.noCaseDrop=player.isCreative();boolean removed=l.setBlock(pos,fluid.createLegacyBlock(),11);if(!removed)pc.noCaseDrop=false;return removed;
    }
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moved){
        if(!s.is(next.getBlock())&&!l.isClientSide&&!l.restoringBlockSnapshots){
            if(s.getValue(HALF)==DoubleBlockHalf.LOWER&&l.getBlockEntity(p) instanceof ComputerEntity pc&&!pc.removing){
                pc.removing=true;pc.dropContents();HomeHardware.removed(l,p);
                if(l.getBlockState(p.above()).is(this)&&l.getBlockState(p.above()).getValue(HALF)==DoubleBlockHalf.UPPER)l.removeBlock(p.above(),false);
            }else if(s.getValue(HALF)==DoubleBlockHalf.UPPER&&l.getBlockEntity(p.below()) instanceof ComputerEntity pc&&!pc.removing)l.destroyBlock(p.below(),false);
        }super.onRemove(s,l,p,next,moved);
    }
    @Override public PushReaction getPistonPushReaction(BlockState s){return PushReaction.BLOCK;}
}
