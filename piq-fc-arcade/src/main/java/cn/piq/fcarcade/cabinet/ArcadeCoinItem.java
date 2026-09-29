package cn.piq.fcarcade.cabinet;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Consumes the item interaction first; holding a coin can never accidentally leave/start a cabinet. */
public final class ArcadeCoinItem extends Item {
    public ArcadeCoinItem(Properties properties){super(properties);}
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){return activate(context.getPlayer(),context.getHand());}
    @Override public InteractionResult useOn(UseOnContext context){return activate(context.getPlayer(),context.getHand());}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){return new InteractionResultHolder<>(activate(player,hand),player.getItemInHand(hand));}
    private InteractionResult activate(Player player,InteractionHand hand){
        if(player instanceof ServerPlayer server)CabinetCoinService.use(server,hand,this);
        else if(player!=null&&player.isAlive()&&!player.isSpectator()&&!player.isUsingItem()&&!player.getCooldowns().isOnCooldown(this))player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }
    @Override public int getUseDuration(ItemStack stack,LivingEntity entity){return 72000;}
    @Override public UseAnim getUseAnimation(ItemStack stack){return UseAnim.NONE;}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("tooltip.piq_fc_arcade.arcade_coin.use"));
        lines.add(Component.translatable("tooltip.piq_fc_arcade.arcade_coin.rules"));
    }
}
