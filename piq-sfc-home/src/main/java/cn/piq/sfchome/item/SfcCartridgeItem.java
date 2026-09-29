package cn.piq.sfchome.item;
import cn.piq.fcarcade.home.CartridgeComputerBlockEntity;
import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.server.SfcCartridgeEditorService;
import cn.piq.sfchome.server.SfcHomeServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
public final class SfcCartridgeItem extends Item {
    public SfcCartridgeItem(Properties p){super(p);}
    @Override public net.minecraft.network.chat.Component getName(ItemStack stack){String title=SfcCartridgeData.title(stack);return title.isEmpty()?super.getName(stack):net.minecraft.network.chat.Component.literal(title);}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,TooltipFlag flag){super.appendHoverText(stack,context,lines,flag);lines.add(net.minecraft.network.chat.Component.literal(SfcCartridgeData.hasExplicitPlayerCount(stack)?"SFC · "+SfcCartridgeData.maxPlayers(stack)+" 人":"SFC · 旧卡（单人可直接开始）"));}
    @Override public InteractionResult onItemUseFirst(ItemStack s,UseOnContext c){if(c.getLevel().getBlockEntity(c.getClickedPos()) instanceof CartridgeComputerBlockEntity){if(c.getPlayer() instanceof ServerPlayer p)SfcCartridgeEditorService.openAt(p,c.getHand(),c.getClickedPos());return InteractionResult.sidedSuccess(c.getLevel().isClientSide);}return InteractionResult.PASS;}
    @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand h){if(p instanceof ServerPlayer sp&&p.isShiftKeyDown())SfcHomeServer.feedback(sp,"手持卡带右键老式电脑写入 SFC 游戏");return InteractionResultHolder.pass(p.getItemInHand(h));}
    @Override public void inventoryTick(ItemStack s,Level l,Entity e,int slot,boolean selected){if(!l.isClientSide&&SfcCartridgeData.supported(s))SfcCartridgeData.ensureId(s);}
}
