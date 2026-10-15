// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.sfcarcade.registry;

import cn.piq.sfcarcade.SfcArcadeMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, SfcArcadeMod.MOD_ID);

    public static final DeferredHolder<Item, BlockItem> SFC_ARCADE =
            ITEMS.register("sfc_arcade", () -> new BlockItem(
                    ModBlocks.SFC_ARCADE.get(), new Item.Properties()));

    private ModItems() {
    }
}
