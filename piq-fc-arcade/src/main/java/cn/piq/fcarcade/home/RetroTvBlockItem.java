package cn.piq.fcarcade.home;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla placement UX and debit, with an atomic eight-cell placeBlock transaction. */
public final class RetroTvBlockItem extends BlockItem {
    public RetroTvBlockItem(Block block, Properties properties) { super(block, properties); }

    @Override public InteractionResult place(BlockPlaceContext context) {
        // TV ownership is always freshly minted. Do not accept components that
        // vanilla would apply after the eight-cell transaction and rotate/re-ID it.
        if (context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)
                || context.getItemInHand().has(DataComponents.BLOCK_STATE)) {
            if (context.getPlayer() instanceof ServerPlayer player)
                player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.home_tv_clean_item"));
            return InteractionResult.FAIL;
        }
        var result = super.place(context);
        if (result == InteractionResult.FAIL && context.getPlayer() instanceof ServerPlayer player)
            player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.home_tv_space"));
        return result;
    }

    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        return HomeTvStructure.place(context, state);
    }
}
