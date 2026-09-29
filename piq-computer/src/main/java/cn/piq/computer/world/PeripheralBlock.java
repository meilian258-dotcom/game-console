package cn.piq.computer.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class PeripheralBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public final boolean keyboard;
    public final boolean combined;
    public static final MapCodec<PeripheralBlock> CODEC=RecordCodecBuilder.mapCodec(i->i.group(Codec.BOOL.fieldOf("keyboard").forGetter((PeripheralBlock b)->b.keyboard),Codec.BOOL.optionalFieldOf("combined",false).forGetter(b->b.combined),propertiesCodec()).apply(i,PeripheralBlock::new));
    public PeripheralBlock(boolean keyboard,Properties p){this(keyboard,false,p);}
    public PeripheralBlock(boolean keyboard,boolean combined,Properties p){super(p);this.keyboard=keyboard;this.combined=combined;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return keyboard?(s.getValue(FACING).getAxis()==Direction.Axis.Z?box(0,0,4.6,16,1.4,11.4):box(4.6,0,0,11.4,1.4,16)):(s.getValue(FACING).getAxis()==Direction.Axis.Z?box(6.5,0,5.4,9.5,1.8,10.6):box(5.4,0,6.5,10.6,1.8,9.5));}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new PeripheralEntity(p,s);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos pos,Player player,BlockHitResult hit){
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return InteractionResult.PASS;
        if(player instanceof ServerPlayer p&&ComputerAccess.near(p,pos)&&l.getBlockEntity(pos) instanceof PeripheralEntity e){
            if(p.isShiftKeyDown()){var pc=e.connected();if(pc==null||ComputerAccess.allowed(p,pc.getBlockPos())){e.unlink();ComputerEntity.say(p,"外设已断开");}}
            else{var pc=e.connected();if(pc!=null)pc.control(p,e);else ComputerEntity.say(p,"用外设连接器依次右键主机和键鼠");}
        }return InteractionResult.sidedSuccess(l.isClientSide);
    }
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moved){if(!s.is(next.getBlock())&&!l.isClientSide&&l.getBlockEntity(p) instanceof PeripheralEntity e)e.unlink();super.onRemove(s,l,p,next,moved);}
    @Override public PushReaction getPistonPushReaction(BlockState s){return PushReaction.BLOCK;}
}
