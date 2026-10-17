// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.registry;

import java.util.Collection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab.TabVisibility;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Narrow compatibility for old PvZ addons that still add their retired test box. */
public final class RetiredCreativeEntries {
    private static final ResourceLocation LEGACY_PVZ_BOX = ResourceLocation.parse("piq_pvz:player_box");

    private RetiredCreativeEntries() {}

    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        hide(event, LEGACY_PVZ_BOX);
    }

    static void hide(BuildCreativeModeTabContentsEvent event, ResourceLocation itemId) {
        if (!CreativeTabGroups.isTargetTab(event)) return;
        removeMatches(event, event.getParentEntries(), itemId, TabVisibility.PARENT_TAB_ONLY);
        removeMatches(event, event.getSearchEntries(), itemId, TabVisibility.SEARCH_TAB_ONLY);
    }

    private static void removeMatches(BuildCreativeModeTabContentsEvent event, Collection<ItemStack> entries,
                                      ResourceLocation itemId, TabVisibility visibility) {
        // Snapshot matching entries only, including component-bearing variants. All others stay put.
        var retired = entries.stream()
                .filter(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)).toList();
        for (var stack : retired) event.remove(stack, visibility);
    }
}
