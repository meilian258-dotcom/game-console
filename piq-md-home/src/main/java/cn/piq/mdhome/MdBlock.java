// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.*;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class MdBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final BooleanProperty INSERTED=BooleanProperty.create("inserted"),BORROWED=BooleanProperty.create("borrowed");
    public static final MapCodec<MdBlock> CODEC=simpleCodec(MdBlock::new);
    public MdBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(INSERTED,false).setValue(BORROWED,false));}
    protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING,INSERTED,BORROWED);}
    public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    protected BlockState mirror(BlockState s,Mirror m){return rotate(s,m.getRotation(s.getValue(FACING)));}
    protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return Block.box(0,0,0,16,s.getValue(INSERTED)?6.5:3,16);}
    public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new MdConsole(p,s);}
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){return !l.isClientSide&&t==MdMod.ENTITY.get()?(w,p,b,e)->((MdConsole)e).tick():null;}
    protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        for(var held:java.util.List.of(player.getMainHandItem(),player.getOffhandItem()))
            if(held.getItem() instanceof AvCableItem||held.is(MdMod.CARTRIDGE.get()))return InteractionResult.PASS;
        if(player instanceof ServerPlayer server){var button=HomeApplianceService.tryButton(server,p,InteractionHand.MAIN_HAND,hit);if(button!=InteractionResult.PASS)return button;}
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return InteractionResult.PASS;
        if(player instanceof ServerPlayer server)HomeSystems.interact(server,p,InteractionHand.MAIN_HAND,hit);
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
    protected ItemInteractionResult useItemOn(ItemStack stack,BlockState s,Level l,BlockPos p,Player player,InteractionHand hand,BlockHitResult hit){
        // Resolve AV on the block itself, before empty-hand/default interactions can consume it.
        if(stack.getItem() instanceof AvCableItem){
            if(player instanceof ServerPlayer server)HomeHardware.useCable(server,p,hand,hit);
            return ItemInteractionResult.sidedSuccess(l.isClientSide);
        }
        if(stack.getItem() instanceof cn.piq.fcarcade.cabinet.CabinetLinkCableItem){
            if(player instanceof ServerPlayer server)server.displayClientMessage(net.minecraft.network.chat.Component.literal("这是街机通讯线；MD 接电视请用 AV 线。"),false);
            return ItemInteractionResult.sidedSuccess(l.isClientSide);
        }
        if(!stack.is(MdMod.CARTRIDGE.get())){
            var button=HomeApplianceService.tryItemButton(stack,l,p,player,hand,hit);
            if(button!=ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)return button;
        }
        if(!stack.is(MdMod.CARTRIDGE.get())&&!stack.is(MdMod.CONTROLLER.get()))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(player instanceof ServerPlayer server)HomeSystems.interact(server,p,hand,hit);
        return ItemInteractionResult.sidedSuccess(l.isClientSide);
    }
    protected void onRemove(BlockState s,Level l,BlockPos p,BlockState n,boolean moving){
        if(s.getBlock()!=n.getBlock()){
            if(!l.isClientSide&&l.getBlockEntity(p) instanceof MdConsole c)c.dropCartridge();
            HomeHardware.removed(l,p);
        }
        super.onRemove(s,l,p,n,moving);
    }
}
