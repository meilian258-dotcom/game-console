// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.world;

import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Independent native cabinet, never an FcArcadeBlock or FC server session owner. */
public final class NativeCabinetBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<NativeCabinetBlock> CODEC=simpleCodec(NativeCabinetBlock::new);
    public NativeCabinetBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        var state=defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());
        return NativeCabinetStructure.canPlace(context,state)?state:null;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new NativeCabinetBlockEntity(pos,state);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return !level.isClientSide&&type==NativeArcadeRegistries.CABINET_ENTITY.get()?(w,p,s,e)->((NativeCabinetBlockEntity)e).maintenanceTick():null;
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moved){
        super.onPlace(state,level,pos,old,moved);NativeCabinetStructure.confirmPlacement(level,pos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        return player instanceof ServerPlayer server?NativeCabinetStructure.interact(server,pos,hit):InteractionResult.SUCCESS;
    }
    @Override public boolean onDestroyedByPlayer(BlockState state,Level level,BlockPos pos,Player player,boolean harvest,FluidState fluid){return NativeCabinetStructure.breakByPlayer(level,pos,state,player,harvest,fluid);}
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moved){
        try{if(state.getBlock()!=replacement.getBlock())NativeCabinetStructure.removed(level,pos);}finally{super.onRemove(state,level,pos,replacement,moved);}
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return NativeCabinetStructure.shape(state,0);}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return NativeCabinetStructure.collisionShape(state,0);}
    @Override protected List<ItemStack> getDrops(BlockState state,LootParams.Builder context){return List.of();}
}
