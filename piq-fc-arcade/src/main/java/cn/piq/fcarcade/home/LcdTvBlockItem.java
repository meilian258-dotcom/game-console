package cn.piq.fcarcade.home;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Vanilla one-cell placement and debit, with fresh link ownership on every placement. */
public final class LcdTvBlockItem extends BlockItem {
    public LcdTvBlockItem(Block block, Properties properties) { super(block, properties); }
    @Override public InteractionResult place(BlockPlaceContext context) {
        if (context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)
                || context.getItemInHand().has(DataComponents.BLOCK_STATE)) {
            if (context.getPlayer() instanceof ServerPlayer player)
                player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.home_lcd_clean_item"));
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }
}
