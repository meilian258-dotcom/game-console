package cn.piq.computer.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ComputerAccess {
    private static final ThreadLocal<Boolean> CHECK=ThreadLocal.withInitial(()->false);
    private ComputerAccess(){}
    public static boolean valid(ServerPlayer p,BlockPos pos,double distance){return p.isAlive()&&!p.isSpectator()&&!p.hasDisconnected()&&p.serverLevel().hasChunkAt(pos)&&p.serverLevel().mayInteract(p,pos)&&p.distanceToSqr(Vec3.atCenterOf(pos))<=distance*distance;}
    public static boolean near(ServerPlayer p,BlockPos pos){return valid(p,pos,Math.min(6,p.blockInteractionRange()+1));}
    /** Protect remotely affected blocks too, using the same cancellable event as normal interaction. */
    public static boolean allowed(ServerPlayer p,BlockPos pos){
        if(CHECK.get()||!valid(p,pos,8))return false;
        var hit=new BlockHitResult(Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
        CHECK.set(true);try{
            var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,hit));
            return !e.isCanceled()&&e.getUseBlock()!=TriState.FALSE&&e.getUseItem()!=TriState.FALSE&&valid(p,pos,8);
        }finally{CHECK.remove();}
    }
}
