package cn.piq.fcarcade.home.content;

import cn.piq.fcarcade.home.CartridgeComputerBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.network.chat.Component;

public class ContentCartridgeItem extends Item {
    private final ResourceLocation system;
    public ContentCartridgeItem(Properties properties,ResourceLocation system){super(properties);this.system=system;}
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){
        if(!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CartridgeComputerBlockEntity))return InteractionResult.PASS;
        if(context.getPlayer() instanceof ServerPlayer p)ContentCards.open(p,context.getHand(),context.getClickedPos(),system);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,java.util.List<Component> lines,TooltipFlag flag){
        var entry=ContentCardData.read(stack,system);
        lines.add(Component.literal(entry==null?"空卡：手持右键老式电脑写入游戏":ContentCardData.title(stack)));
        lines.add(Component.literal("写卡沿用管理终端的游戏库权限；卡带不包含游戏文件"));
    }
}
