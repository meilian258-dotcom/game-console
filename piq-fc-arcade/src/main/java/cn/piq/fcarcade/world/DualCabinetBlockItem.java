package cn.piq.fcarcade.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla placement UX and debit, with an atomic twelve-cell placeBlock transaction. */
public final class DualCabinetBlockItem extends BlockItem {
    public DualCabinetBlockItem(Block block, Properties properties) { super(block, properties); }

    @Override public InteractionResult place(BlockPlaceContext context) {
        // DualCabinet ownership is always freshly minted. Do not accept components that
        // vanilla would apply after the twelve-cell transaction and rotate/re-ID it.
        if (context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)
                || context.getItemInHand().has(DataComponents.BLOCK_STATE)) {
            if (context.getPlayer() instanceof ServerPlayer player)
                player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.dual_cabinet_clean_item"));
            return InteractionResult.FAIL;
        }
        var result = super.place(context);
        if (result == InteractionResult.FAIL && context.getPlayer() instanceof ServerPlayer player)
            player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.dual_cabinet_space"));
        return result;
    }

    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        return DualCabinetStructure.place(context, state);
    }
}

