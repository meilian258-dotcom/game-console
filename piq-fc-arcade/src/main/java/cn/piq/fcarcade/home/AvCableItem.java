package cn.piq.fcarcade.home;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import cn.piq.fcarcade.world.FamicomConsoleBlock;
import net.minecraft.world.phys.BlockHitResult;

public final class AvCableItem extends Item {
    public AvCableItem(Properties properties) { super(properties); }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> tooltip,net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.piq_fc_arcade.av_cable.connect"));
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.piq_fc_arcade.av_cable.disconnect"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player)
            return HomeHardware.useCable(player, context.getClickedPos(), context.getHand(),
                    new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), false));
        var block = context.getLevel().getBlockState(context.getClickedPos()).getBlock();
        boolean hardware = block instanceof FamicomConsoleBlock || block instanceof RetroTvBlock
                || block instanceof HomeTvPartBlock || block instanceof WideLcdTvPartBlock || block instanceof LargeLcdTvPartBlock || block instanceof PanelTvPartBlock || block instanceof SuborPartBlock
                || HomeHardware.loadedEndpoint(context.getLevel(), context.getClickedPos()) instanceof ExternalHomeConsoleBlockEntity;
        return context.getLevel().isClientSide && hardware ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
