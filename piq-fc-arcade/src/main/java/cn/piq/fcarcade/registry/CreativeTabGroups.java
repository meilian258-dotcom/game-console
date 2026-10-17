// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.registry;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.furniture.WoodSpecies;
import java.util.Arrays;
import java.util.Collection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab.TabVisibility;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Adds only the caller's entries at a main-mod boundary through NeoForge's public event API. */
public final class CreativeTabGroups {
    private static final ResourceLocation TAB = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "fc");

    public enum Group {
        CONSOLES("legacy_fc_arcade"),
        ARCADE("retro_tv"),
        COMPUTERS("fc_cartridge"),
        MEDIA("av_cable"),
        ACCESSORIES("tv_remote"),
        TOOLS("furniture/" + WoodSpecies.values()[0].id() + "_bench");

        private final ResourceLocation anchor;

        Group(String path) {
            anchor = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, path);
        }
    }

    private CreativeTabGroups() {}

    /**
     * Insert the supplied sequence in both views of the FC tab. Each contribution keeps its own
     * order; different addons in the same group keep event dispatch order. If another mod removes
     * the boundary, append in that view. Existing entries are never moved or replaced.
     */
    public static void insert(BuildCreativeModeTabContentsEvent event, Group group, ItemLike... items) {
        if (!isTargetTab(event)) return;
        insert(event, group, TabVisibility.PARENT_AND_SEARCH_TABS,
                Arrays.stream(items).map(ItemStack::new).toArray(ItemStack[]::new));
    }

    /** Preserve the supplied stacks' components and requested visibility; duplicates are per view. */
    public static void insert(BuildCreativeModeTabContentsEvent event, Group group,
                              TabVisibility visibility, ItemStack... stacks) {
        if (!isTargetTab(event)) return;
        insertBefore(event, group.anchor, visibility, stacks);
    }

    static boolean isTargetTab(BuildCreativeModeTabContentsEvent event) {
        return event.getTabKey().location().equals(TAB);
    }

    static void insertBefore(BuildCreativeModeTabContentsEvent event, ResourceLocation anchor,
                             TabVisibility visibility, ItemStack... stacks) {
        if (!isTargetTab(event)) return;
        for (var stack : stacks) {
            if (stack.getCount() != 1) throw new IllegalArgumentException("Creative stack count must be 1");
        }
        if (visibility != TabVisibility.SEARCH_TAB_ONLY)
            insertInto(event, event.getParentEntries(), anchor, TabVisibility.PARENT_TAB_ONLY, stacks);
        if (visibility != TabVisibility.PARENT_TAB_ONLY)
            insertInto(event, event.getSearchEntries(), anchor, TabVisibility.SEARCH_TAB_ONLY, stacks);
    }

    private static void insertInto(BuildCreativeModeTabContentsEvent event, Collection<ItemStack> entries,
                                   ResourceLocation anchorId, TabVisibility visibility, ItemStack[] stacks) {
        var anchor = entries.stream()
                .filter(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(anchorId))
                .findFirst().orElse(null);
        for (var stack : stacks) {
            // NeoForge uses item + component equality here, so different variants remain distinct.
            if (entries.contains(stack)) continue;
            if (anchor == null) event.accept(stack, visibility);
            else event.insertBefore(anchor, stack, visibility);
        }
    }
}
