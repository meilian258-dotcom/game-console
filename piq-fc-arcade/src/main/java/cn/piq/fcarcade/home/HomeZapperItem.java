package cn.piq.fcarcade.home;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

/** A physical accessory, never an input-authority token by itself. */
public final class HomeZapperItem extends Item {
    public HomeZapperItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getHand()!=InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) return HomeZapperService.useOn(player,context);
        return context.getPlayer()==null?InteractionResult.PASS:InteractionResult.SUCCESS;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        if (hand==InteractionHand.MAIN_HAND && !level.isClientSide)
            player.displayClientMessage(Component.translatable("message.piq_fc_arcade.zapper_bind_hint"),true);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public void inventoryTick(ItemStack stack,Level level,Entity entity,int slot,boolean selected) {
        if (entity instanceof ServerPlayer player) HomeZapperService.inventoryTick(player,stack);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.translatable("message.piq_fc_arcade.zapper_bind_hint"));
        lines.add(Component.translatable("message.piq_fc_arcade.zapper_fire_hint"));
    }
}
