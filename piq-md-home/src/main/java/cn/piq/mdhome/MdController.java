// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import java.util.UUID;
public final class MdController extends Item {
    public MdController(Properties p){super(p);}
    public static UUID loan(ItemStack s){if(!s.is(MdMod.CONTROLLER.get()))return null;var d=s.get(DataComponents.CUSTOM_DATA);return d!=null&&d.copyTag().hasUUID("MdLoan")?d.copyTag().getUUID("MdLoan"):null;}
    @Override public void appendHoverText(ItemStack s,TooltipContext c,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag f){lines.add(net.minecraft.network.chat.Component.literal("MD2 借用1P手柄 · 6格内 · /gameconsole-private"));lines.add(net.minecraft.network.chat.Component.literal("右键主机归还；失效凭据不能重借或控制"));}
}
