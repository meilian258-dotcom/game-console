package cn.piq.fcarcade.home;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A reusable remote: aiming is always resolved again by the server. */
public final class TvRemoteItem extends Item {
    public TvRemoteItem(Properties properties) { super(properties); }

    // NeoForge invokes this before a block's own GUI/game interaction. Consume
    // even a miss so using a remote cannot accidentally operate another machine.
    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return activate(context.getPlayer(), context.getHand());
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        return activate(context.getPlayer(), context.getHand());
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(activate(player, hand), player.getItemInHand(hand));
    }

    private InteractionResult activate(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) return TvRemoteService.use(serverPlayer, hand, this);
        if (player != null && player.level().isClientSide && player.isAlive() && !player.isSpectator()
                && player.getItemInHand(hand).is(this) && !player.getCooldowns().isOnCooldown(this))
            player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    // Vanilla consumes repeat right-clicks while the button is held, and releases
    // this latch when the player releases use. No eating/bow pose or item loss.
    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) { return TvRemotePolicy.HOLD_TICKS; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.piq_fc_arcade.tv_remote"));
    }
}
