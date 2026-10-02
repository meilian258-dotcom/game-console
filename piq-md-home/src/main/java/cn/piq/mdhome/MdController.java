// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import java.util.UUID;
public final class MdController extends Item {
    public MdController(Properties p){super(p);}
    public static UUID loan(ItemStack s){if(!s.is(MdMod.CONTROLLER.get()))return null;var d=s.get(DataComponents.CUSTOM_DATA);return d!=null&&d.copyTag().hasUUID("MdLoan")?d.copyTag().getUUID("MdLoan"):null;}
    public static int port(ItemStack s){var d=s.get(DataComponents.CUSTOM_DATA);return d==null?-1:d.copyTag().getInt("MdPort");}
    private static MdConsole console(net.minecraft.server.level.ServerPlayer p,ItemStack item){
        var data=item.get(DataComponents.CUSTOM_DATA);if(data==null)return null;var t=data.copyTag();
        var dim=net.minecraft.resources.ResourceLocation.tryParse(t.getString("MdDimension"));if(dim==null||!t.hasUUID("MdConsole"))return null;
        var world=p.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dim));
        var pos=net.minecraft.core.BlockPos.of(t.getLong("MdPos"));
        return world!=null&&world.hasChunkAt(pos)&&world.getBlockEntity(pos) instanceof MdConsole c&&c.hardwareId().equals(t.getUUID("MdConsole"))?c:null;
    }
    public static void tossed(net.neoforged.neoforge.event.entity.item.ItemTossEvent event){
        if(event.isCanceled()||!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer p))return;
        var item=event.getEntity().getItem();if(!item.is(MdMod.CONTROLLER.get()))return;
        var id=loan(item);var c=console(p,item);
        int port=port(item);if(id!=null&&c!=null&&id.equals(c.loan(port))&&p.getUUID().equals(c.borrower(port)))c.clearLoan(port);
        item.setCount(0);event.getEntity().discard();event.setCanceled(true);
    }
    public static void drops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event){
        if(!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p))return;
        event.getDrops().removeIf(drop->{
            var item=drop.getItem();if(!item.is(MdMod.CONTROLLER.get()))return false;
            var c=console(p,item);var id=loan(item);
            int port=port(item);if(c!=null&&id!=null&&id.equals(c.loan(port))&&p.getUUID().equals(c.borrower(port)))c.clearLoan(port);
            drop.discard();return true;
        });
    }
    @Override public void inventoryTick(ItemStack stack,net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity entity,int slot,boolean selected){
        if(!(entity instanceof net.minecraft.server.level.ServerPlayer p)||p.tickCount%10!=0)return;
        var id=loan(stack);var c=console(p,stack);
        if(id==null||c==null||!c.authorized(p,port(stack),id,false))stack.setCount(0);
    }
    @Override public void appendHoverText(ItemStack s,TooltipContext c,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag f){lines.add(net.minecraft.network.chat.Component.literal("MD2 · "+(port(s)+1)+"P 手柄 · 6格内 · 主机电源独立控制"));lines.add(net.minecraft.network.chat.Component.literal("右键原主机机身或 Q 归还；归还不关机"));}
}
