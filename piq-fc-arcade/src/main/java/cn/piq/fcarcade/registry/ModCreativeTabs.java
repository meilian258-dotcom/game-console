package cn.piq.fcarcade.registry;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.furniture.FurnitureRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Ordinary creative inventory page; never grants survival-mode items. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FcArcadeMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FC = TABS.register("fc",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.piq_fc_arcade"))
                    .icon(() -> new ItemStack(ModItems.FAMICOM_CONSOLE.get()))
                    // NeoForge default places unpinned mod tabs after the vanilla categories.
                    .displayItems(ModCreativeTabs::displayItems)
                    .build());

    private ModCreativeTabs() {
    }

    private static void displayItems(CreativeModeTab.ItemDisplayParameters parameters,
                                     CreativeModeTab.Output output) {
        for (String path : CreativeTabCatalog.itemPaths(ModList.get().isLoaded("waterframes"))) {
            var id = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, path);
            output.accept(BuiltInRegistries.ITEM.getOptional(id)
                    .orElseThrow(() -> new IllegalStateException("Unregistered FC creative item: " + id)));
        }
        FurnitureRegistry.addCreativeItems(output);
    }
}
