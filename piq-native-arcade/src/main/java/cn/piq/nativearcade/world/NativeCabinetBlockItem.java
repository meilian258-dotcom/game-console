// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla placement UX and debit, with an atomic six-cell placeBlock transaction. */
public final class NativeCabinetBlockItem extends BlockItem {
    public NativeCabinetBlockItem(Block block, Properties properties) { super(block, properties); }

    @Override public InteractionResult place(BlockPlaceContext context) {
        // NativeCabinet ownership is always freshly minted. Do not accept components that
        // vanilla would apply after the six-cell transaction and rotate/re-ID it.
        if (context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)
                || context.getItemInHand().has(DataComponents.BLOCK_STATE)) {
            if (context.getPlayer() instanceof ServerPlayer player)
                player.sendSystemMessage(Component.translatable("message.piq_native_arcade.cabinet_clean_item"));
            return InteractionResult.FAIL;
        }
        var result = super.place(context);
        if (result == InteractionResult.FAIL && context.getPlayer() instanceof ServerPlayer player)
            player.sendSystemMessage(Component.translatable("message.piq_native_arcade.cabinet_space"));
        return result;
    }

    @Override protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        return NativeCabinetStructure.place(context, state);
    }
}

