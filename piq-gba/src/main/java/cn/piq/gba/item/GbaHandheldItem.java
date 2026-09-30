// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

/** An ordinary single item: never creates a server core, a cabinet lease or a loaned controller. */
public final class GbaHandheldItem extends Item {
    public GbaHandheldItem(Properties properties){super(properties);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("tooltip.piq_gba.handheld.use"));
        lines.add(Component.translatable("tooltip.piq_gba.handheld.local"));
        var card=GbaCartridgeSlot.card(stack);
        lines.add(Component.literal(card.isEmpty()?"未插卡：掌机和卡带分持两手，右键插入":"已插卡："+cn.piq.fcarcade.home.content.ContentCardData.title(card)));
        lines.add(Component.literal("设置/退卡：/gameconsole-gba，或绑定“GBA 掌机设置”按键"));
    }
}
