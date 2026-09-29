package cn.piq.j2mearcade.registry;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, PiqJ2meArcadeMod.MOD_ID);

    public static final DeferredHolder<Item, BlockItem> J2ME_ARCADE =
            ITEMS.register("j2me_arcade", () -> new BlockItem(
                    ModBlocks.J2ME_ARCADE.get(), new Item.Properties()));

    private ModItems() {
    }
}
