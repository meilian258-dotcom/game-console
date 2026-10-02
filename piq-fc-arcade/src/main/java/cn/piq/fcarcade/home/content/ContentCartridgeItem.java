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
        if(ContentCards.features(system).localSaveSettings()){
            lines.add(Component.literal("右键主机插卡；右键老式电脑编辑 / 封面 / 保存方式"));
            int mode=ContentCardData.saveMode(stack);
            lines.add(Component.literal(mode==0?"【不存档】":ContentCards.features(system).publicSaves()
                    ?mode==1?"【公开卡带存档】":"【公开个人存档；私人本机档独立】":"【个人本机存档；服务器存档尚未接入】"));
            if(ContentCards.features(system).maxPlayers()>1)lines.add(Component.literal("人数标签："+ContentCardData.players(stack)+" 人（不改变游戏自身能力）"));
            if(entry!=null&&flag.isAdvanced())lines.add(Component.literal("游戏 ROM · "+entry.hash().substring(0,12)));
        }
        lines.add(Component.literal("写卡沿用管理终端的游戏库权限；卡带不包含游戏文件"));
    }
}
