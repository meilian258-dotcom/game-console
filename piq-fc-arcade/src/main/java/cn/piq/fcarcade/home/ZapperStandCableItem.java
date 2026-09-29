package cn.piq.fcarcade.home;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import cn.piq.fcarcade.cabinet.CabinetLinks;
import cn.piq.fcarcade.cabinet.CabinetTarget;
import java.util.List;

/** Shared data cable. Existing item IDs remain valid; only recognized endpoints reach their old services. */
public class ZapperStandCableItem extends Item {
    public ZapperStandCableItem(Properties p){super(p.stacksTo(1));}
    @Override public InteractionResult useOn(UseOnContext c){
        if(c.getHand()!=InteractionHand.MAIN_HAND||!c.getLevel().hasChunkAt(c.getClickedPos()))return InteractionResult.PASS;
        boolean cabinet=CabinetTarget.resolve(c.getLevel(),c.getClickedPos())!=null;
        boolean stand=c.getLevel().getBlockEntity(c.getClickedPos()) instanceof ZapperStandBlockEntity;
        boolean console=HomeHardware.loadedEndpoint(c.getLevel(),c.getClickedPos()) instanceof HomeConsoleBlockEntity;
        if(!cabinet&&!stand&&!console)return InteractionResult.PASS;
        if(c.getPlayer() instanceof ServerPlayer p){
            if(p.hasDisconnected()||!p.connection.getConnection().isConnected()||p.getMainHandItem()!=c.getItemInHand()
                    ||c.getItemInHand().getCount()!=1)return InteractionResult.CONSUME;
            if(cabinet){
                ZapperStandService.cancelCableSelection(p);
                var hit=new BlockHitResult(c.getClickLocation(),c.getClickedFace(),c.getClickedPos(),c.isInside());
                return CabinetLinks.use(p,c.getClickedPos(),hit,p.isShiftKeyDown())?InteractionResult.CONSUME:InteractionResult.PASS;
            }
            CabinetLinks.cancelSelection(p);
            return ZapperStandService.cable(p,c);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        tooltip.add(Component.translatable("tooltip.piq_fc_arcade.data_cable.connect"));
        tooltip.add(Component.translatable("tooltip.piq_fc_arcade.data_cable.disconnect"));
        tooltip.add(Component.translatable("tooltip.piq_fc_arcade.data_cable.permissions"));
    }
}
