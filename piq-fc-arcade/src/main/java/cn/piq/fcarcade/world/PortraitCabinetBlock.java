package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.session.ArcadeMode;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.common.CommonHooks;
import java.util.List;

/** Two-high, one-player cabinet. Upper half owns no session or duplicate drops. */
public final class PortraitCabinetBlock extends FcArcadeBlock implements EntityBlock {
    public static final BooleanProperty UPPER=BooleanProperty.create("upper");
    public static final MapCodec<PortraitCabinetBlock> CODEC=simpleCodec(PortraitCabinetBlock::new);
    public PortraitCabinetBlock(Properties p){super(p,ArcadeMode.LOCKSTEP,ArcadeDisplayStyle.PORTRAIT_CABINET);registerDefaultState(defaultBlockState().setValue(UPPER,false));}
    @Override protected MapCodec<? extends Block> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(UPPER);}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override public PushReaction getPistonPushReaction(BlockState s){return PushReaction.BLOCK;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return s.getValue(UPPER)?null:new PortraitCabinetBlockEntity(p,s);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        var p=c.getClickedPos();var l=c.getLevel();
        if(p.getY()+1>=l.getMaxBuildHeight()||!l.hasChunkAt(p.above())||!l.getBlockState(p.above()).canBeReplaced(c))return null;
        return super.getStateForPlacement(c).setValue(UPPER,false);
    }
    @Override public void setPlacedBy(Level l,BlockPos p,BlockState s,LivingEntity entity,ItemStack stack){
        super.setPlacedBy(l,p,s,entity,stack);
        if(!s.getValue(UPPER))l.setBlock(p.above(),s.setValue(UPPER,true),Block.UPDATE_ALL);
    }
    public static BlockPos resolveAnchor(Level l,BlockPos clicked){
        if(l==null||!l.hasChunkAt(clicked))return null;
        var s=l.getBlockState(clicked);if(!(s.getBlock() instanceof PortraitCabinetBlock))return null;
        var p=s.getValue(UPPER)?clicked.below():clicked;
        if(!l.hasChunkAt(p)||!l.hasChunkAt(p.above()))return null;
        var bottom=l.getBlockState(p);var top=l.getBlockState(p.above());
        return bottom.is(s.getBlock())&&top.is(s.getBlock())&&!bottom.getValue(UPPER)&&top.getValue(UPPER)
                &&bottom.getValue(FACING)==top.getValue(FACING)&&l.getBlockEntity(p) instanceof PortraitCabinetBlockEntity?p:null;
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(l.isClientSide)return InteractionResult.SUCCESS;
        var anchor=resolveAnchor(l,p);if(anchor==null||!(player instanceof ServerPlayer server))return InteractionResult.FAIL;
        // ServerCabinets validates both clicked and anchor positions/events, including upper-half protection.
        if(ServerCabinets.interact(server,p,hit))return InteractionResult.CONSUME;
        if(ServerCabinets.validatedTarget(server,p,hit)==null)return InteractionResult.FAIL;
        if(player.isShiftKeyDown())ServerArcadeSessions.openLibrary(server,anchor);else ServerArcadeSessions.interact(server,anchor);
        return InteractionResult.CONSUME;
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        var f=s.getValue(FACING);int turns=RocketArcadeGeometry.quarterTurns(f.getStepX(),f.getStepZ());
        int y=s.getValue(UPPER)?1:0;var b=PortraitCabinetGeometry.bounds(turns);
        return Shapes.box(b.minX(),b.minY()-y,b.minZ(),b.maxX(),b.maxY()-y,b.maxZ());
    }
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        var f=s.getValue(FACING);int turns=RocketArcadeGeometry.quarterTurns(f.getStepX(),f.getStepZ());
        int y=s.getValue(UPPER)?1:0;VoxelShape body=Shapes.empty();
        for(var b:CabinetOutlineGeometry.boxes(true,true,turns))
            body=Shapes.or(body,Shapes.box(b.minX(),b.minY()-y,b.minZ(),b.maxX(),b.maxY()-y,b.maxZ()));
        return Shapes.join(body,Shapes.block(),BooleanOp.AND);
    }
    @Override public boolean onDestroyedByPlayer(BlockState s,Level l,BlockPos p,Player player,boolean harvest,FluidState fluid){
        if(l.isClientSide)return super.onDestroyedByPlayer(s,l,p,player,harvest,fluid);
        var anchor=resolveAnchor(l,p);var other=s.getValue(UPPER)?p.below():p.above();
        if(anchor!=null){
            var old=l.getBlockEntity(anchor);var otherState=l.getBlockState(other);
            if(!l.mayInteract(player,other)||player instanceof ServerPlayer sp&&CommonHooks.fireBlockBreak(l,sp.gameMode.getGameModeForPlayer(),sp,other,otherState).isCanceled())return false;
            if(l.getBlockEntity(anchor)!=old||l.getBlockState(p)!=s||l.getBlockState(other)!=otherState)return false;
            // Creative removal of the top must not turn into a survival drop from the bottom.
            if(player.isCreative()&&s.getValue(UPPER))l.removeBlock(anchor,false);
        }
        return super.onDestroyedByPlayer(s,l,p,player,harvest,fluid);
    }
    @Override protected List<ItemStack> getDrops(BlockState s,LootParams.Builder p){return s.getValue(UPPER)?List.of():List.of(new ItemStack(asItem()));}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moved){
        if(!s.is(next.getBlock())&&!l.isClientSide){
            if(l instanceof ServerLevel server&&l.getBlockEntity(p) instanceof PortraitCabinetBlockEntity e){
                ServerCabinets.removed(server,p,e.cabinetId());CabinetLinks.removed(server,p,e.cabinetId());
                ServerArcadeSessions.removeMachineDisplays(server.getServer(),server.dimension(),p);
            }
            var other=s.getValue(UPPER)?p.below():p.above();var state=l.getBlockState(other);
            if(state.is(this)&&state.getValue(UPPER)!=s.getValue(UPPER)&&state.getValue(FACING)==s.getValue(FACING)){
                if(s.getValue(UPPER))l.destroyBlock(other,true);else l.removeBlock(other,false);
            }
        }
        super.onRemove(s,l,p,next,moved);
    }
}
