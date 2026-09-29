package cn.piq.fcarcade.home;

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

/** OP diagnostic tool; every attempt is consumed before the block's gameplay action. */
public final class DeviceDebugItem extends Item {
    public DeviceDebugItem(Properties properties) { super(properties); }
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context) { return activate(context.getPlayer(),context.getHand()); }
    @Override public InteractionResult useOn(UseOnContext context) { return activate(context.getPlayer(),context.getHand()); }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        return new InteractionResultHolder<>(activate(player,hand),player.getItemInHand(hand));
    }
    private InteractionResult activate(Player player,InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) return DeviceDebugService.use(serverPlayer,hand,this);
        if (player != null && player.level().isClientSide && player.hasPermissions(2) && player.isAlive() && !player.isSpectator()
                && player.getItemInHand(hand).is(this) && !player.getCooldowns().isOnCooldown(this)) player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }
    @Override public int getUseDuration(ItemStack stack,LivingEntity entity) { return DeviceDebugPolicy.HOLD_TICKS; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.piq_fc_arcade.debug_screwdriver"));
    }
}
