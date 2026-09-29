package cn.piq.fcarcade.home;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class FcControllerItem extends Item {
    public FcControllerItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public Component getName(ItemStack stack) {
        int port = HomeControllerData.port(stack);
        return port < 0 ? super.getName(stack) : Component.translatable("item.piq_fc_arcade.fc_controller_p" + (port + 1));
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("message.piq_fc_arcade.controller_hint"));
        if (HomeControllerData.leaseId(stack) == null) lines.add(Component.translatable("message.piq_fc_arcade.controller_invalid"));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) HomeControllerService.activate(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) return HomeControllerService.useOn(player, context);
        var block = context.getLevel().getBlockState(context.getClickedPos()).getBlock();
        return block instanceof cn.piq.fcarcade.world.FamicomConsoleBlock || block instanceof RetroTvBlock
                || block instanceof HomeTvPartBlock || block instanceof WideLcdTvPartBlock || block instanceof LargeLcdTvPartBlock || block instanceof PanelTvPartBlock || block instanceof SuborPartBlock
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (entity instanceof ServerPlayer player) HomeControllerService.inventoryTick(player, stack);
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        return HomeControllerService.updateDropped(entity);
    }
}
