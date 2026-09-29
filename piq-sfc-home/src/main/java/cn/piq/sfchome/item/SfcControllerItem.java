package cn.piq.sfchome.item;
import cn.piq.sfchome.server.SfcHomeServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
public final class SfcControllerItem extends Item {
    public SfcControllerItem(Properties p){super(p);}
    // Allow vanilla to report the real inventory removal to ItemTossEvent.
    // The server cancels the entity and returns its verified loan atomically.
    @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand h){if(p instanceof ServerPlayer sp)SfcHomeServer.useController(sp,h);return InteractionResultHolder.sidedSuccess(p.getItemInHand(h),l.isClientSide);}
    @Override public InteractionResult useOn(UseOnContext c){
        if(c.getPlayer() instanceof ServerPlayer p)return SfcHomeServer.useControllerOn(p,c.getHand(),c.getClickedPos(),new BlockHitResult(c.getClickLocation(),c.getClickedFace(),c.getClickedPos(),c.isInside()));
        return c.getLevel().getBlockEntity(c.getClickedPos()) instanceof cn.piq.sfchome.world.SfcHomeConsoleBlockEntity
                ||cn.piq.fcarcade.home.HomeTvStructure.resolveAnchor(c.getLevel(),c.getClickedPos())!=null?InteractionResult.SUCCESS:InteractionResult.PASS;
    }
    @Override public int getUseDuration(ItemStack s,LivingEntity e){return 72000;}
    @Override public UseAnim getUseAnimation(ItemStack s){return UseAnim.NONE;}
    @Override public void inventoryTick(ItemStack s,Level l,Entity e,int slot,boolean selected){if(e instanceof ServerPlayer p)SfcHomeServer.validateControllerItem(p,s);}
    @Override public boolean onEntityItemUpdate(ItemStack s,net.minecraft.world.entity.item.ItemEntity entity){return SfcHomeServer.discardDroppedController(entity);}
}
