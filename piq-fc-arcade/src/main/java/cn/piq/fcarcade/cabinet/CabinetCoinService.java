package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.world.FcArcadeBlock;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-thread physical transaction. No coordinates, coin counts or seat numbers are accepted from a client packet. */
final class CabinetCoinService {
    private static final ThreadLocal<Boolean> CHECKING=ThreadLocal.withInitial(()->false);
    private CabinetCoinService(){}
    private record Hit(CabinetTarget target,BlockHitResult hit,BlockEntity anchor,BlockEntity clicked){}
    static void use(ServerPlayer player,InteractionHand hand,ArcadeCoinItem item){
        var server=player.getServer();var held=player.getItemInHand(hand);
        if(CHECKING.get()||server==null||!server.isSameThread()||!player.isAlive()||player.isSpectator()||player.isUsingItem()
                ||held.isEmpty()||!held.is(item)||player.getCooldowns().isOnCooldown(item))return;
        CHECKING.set(true);
        try{
            // Throttle failures before callbacks/rays, and require release between physical coins.
            player.getCooldowns().addCooldown(item,6);player.startUsingItem(hand);
            var connection=player.connection.getConnection();var level=player.serverLevel();var original=held.copy();int selectedSlot=player.getInventory().selected;
            var hit=target(player);if(hit==null){notice(player,"请对准街机正面的投币口");return;}
            var admission=CabinetRooms.coinAdmission(player,hit.target());
            if(admission==null){notice(player,"未投币：请先加入已就绪的实体投币街机；免费机不消耗银币");return;}
            if(!hit.target().equals(ServerCabinets.validatedTarget(player,hit.hit().getBlockPos(),hit.hit(),hand)))return;
            // Protection listeners can replace items, machines, connections or the whole linked room.
            var after=target(player);
            if(after==null||!hit.target().equals(after.target())||hit.anchor()!=after.anchor()||hit.clicked()!=after.clicked()
                    ||!hit.hit().getBlockPos().equals(after.hit().getBlockPos()))return;
            var fresh=CabinetRooms.coinAdmission(player,hit.target());
            if(fresh==null||fresh.room()!=admission.room()||fresh.member()!=admission.member()||!Objects.equals(fresh.secondary(),admission.secondary())
                    ||fresh.primaryEntity()!=admission.primaryEntity()||fresh.secondaryEntity()!=admission.secondaryEntity())return;
            var unsupported=CabinetRooms.coinSupportProblem(player,fresh);
            if(unsupported!=null){notice(player,unsupported);return;}
            if(player.serverLevel()!=level||player.hasDisconnected()||player.connection.getConnection()!=connection||!connection.isConnected()
                    ||server.getPlayerList().getPlayer(player.getUUID())!=player||!player.isAlive()||player.isSpectator()
                    ||hand==InteractionHand.MAIN_HAND&&player.getInventory().selected!=selectedSlot
                    ||player.getItemInHand(hand)!=held||!held.is(item)||!ItemStack.matches(held,original)||held.getCount()<1
                    ||!hit.target().matches(level)||level.getBlockEntity(hit.target().anchor())!=hit.anchor()
                    ||level.getBlockEntity(hit.hit().getBlockPos())!=hit.clicked()
                    ||!fresh.room().target.matches(level)||level.getBlockEntity(fresh.room().target.anchor())!=fresh.primaryEntity()
                    ||fresh.secondary()!=null&&(!fresh.secondary().matches(level)||level.getBlockEntity(fresh.secondary().anchor())!=fresh.secondaryEntity()))return;
            if(!CabinetRooms.insertCoin(player,fresh)){notice(player,"未投币：模拟器尚未就绪或输入队列繁忙");return;}
            held.shrink(1);player.getInventory().setChanged();player.containerMenu.broadcastChanges();
            level.playSound(null,hit.target().anchor(),SoundEvents.UI_BUTTON_CLICK.value(),SoundSource.BLOCKS,.35F,1.7F);
            notice(player,"已投币 1 枚 → P"+(fresh.member().port+1)+"；游戏点数/续关规则由游戏决定");
        }catch(RuntimeException|LinkageError failure){com.mojang.logging.LogUtils.getLogger().warn("Physical arcade coin rejected",failure);}
        finally{CHECKING.remove();}
    }
    private static void notice(ServerPlayer player,String message){player.displayClientMessage(Component.literal(message),true);}
    private static Hit target(ServerPlayer player){
        var level=player.serverLevel();Vec3 eye=player.getEyePosition(),look=player.getLookAngle();
        double range=Math.min(6,player.blockInteractionRange());
        if(!finite(eye)||!finite(look)||look.lengthSqr()<.99||look.lengthSqr()>1.01||!Double.isFinite(range)||range<=0)return null;
        Vec3 end=eye.add(look.normalize().scale(range));
        var hit=new LoadedBlocks(level).clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.BLOCK||!level.hasChunkAt(hit.getBlockPos())||!level.getWorldBorder().isWithinBounds(hit.getBlockPos()))return null;
        var target=CabinetTarget.resolve(level,hit.getBlockPos());if(target==null||!level.getWorldBorder().isWithinBounds(target.anchor()))return null;
        var state=level.getBlockState(target.anchor());if(!state.hasProperty(FcArcadeBlock.FACING))return null;
        var facing=state.getValue(FcArcadeBlock.FACING);var origin=Vec3.atLowerCornerOf(target.anchor());var a=eye.subtract(origin);var b=end.subtract(origin);
        boolean compact=level.getBlockEntity(target.anchor()) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint();
        boolean portrait=level.getBlockEntity(target.anchor()) instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity;
        if(portrait){
            int turn=RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ());
            var start=cn.piq.fcarcade.layout.PortraitCabinetGeometry.source(RocketArcadeGeometry.rotate(new Point(a.x,a.y,a.z),-turn));
            var portraitEnd=cn.piq.fcarcade.layout.PortraitCabinetGeometry.source(RocketArcadeGeometry.rotate(new Point(b.x,b.y,b.z),-turn));
            if(!CabinetCoinGeometry.hits(false,0,start,portraitEnd,false))return null;
        }else if(!CabinetCoinGeometry.hits(target.dual(),RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ()),new Point(a.x,a.y,a.z),new Point(b.x,b.y,b.z),compact))return null;
        return new Hit(target,hit,level.getBlockEntity(target.anchor()),level.getBlockEntity(hit.getBlockPos()));
    }
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private record LoadedBlocks(ServerLevel level)implements BlockGetter{
        @Override public BlockState getBlockState(BlockPos pos){return level.hasChunkAt(pos)?level.getBlockState(pos):Blocks.BARRIER.defaultBlockState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return level.hasChunkAt(pos)?level.getBlockEntity(pos):null;}
        @Override public FluidState getFluidState(BlockPos pos){return level.hasChunkAt(pos)?level.getFluidState(pos):Fluids.EMPTY.defaultFluidState();}
        @Override public int getHeight(){return level.getHeight();}
        @Override public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
