// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.registry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.util.InsertableLinkedOpenCustomHashSet;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static net.minecraft.world.item.CreativeModeTab.TabVisibility.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real NeoForge insertion, component equality and independent parent/search membership. */
class CreativeTabGroupsTest {
    private static final ResourceKey<CreativeModeTab> OUR_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.parse("piq_fc_arcade:fc"));
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");

    @BeforeAll static void bootstrap() {
        if (LoadingModList.get() == null)
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void multipleContributionsKeepTheirSequenceAndExistingEntriesInPlace() {
        var event = fixture(OUR_TAB);
        var first = new ItemStack(Items.APPLE);
        var anchor = new ItemStack(Items.STONE);
        var last = new ItemStack(Items.DIRT);
        var contributionOne = namedPaper("one");
        var contributionTwo = namedPaper("two");
        var laterAddon = namedPaper("later addon");
        event.accept(first);
        event.accept(anchor);
        event.accept(last);

        CreativeTabGroups.insertBefore(event, STONE, PARENT_AND_SEARCH_TABS, contributionOne, contributionTwo);
        CreativeTabGroups.insertBefore(event, STONE, PARENT_AND_SEARCH_TABS, laterAddon);

        var expected = List.of(first, contributionOne, contributionTwo, laterAddon, anchor, last);
        assertReferences(expected, event.getParentEntries());
        assertReferences(expected, event.getSearchEntries());
        // Existing event-owned collections remain usable through the supported APIs.
        event.remove(laterAddon, PARENT_AND_SEARCH_TABS);
        event.insertAfter(anchor, laterAddon, PARENT_AND_SEARCH_TABS);
        assertReferences(List.of(first, contributionOne, contributionTwo, anchor, laterAddon, last), event.getParentEntries());
    }

    @Test void independentAnchorsAndDuplicateMembershipPreserveComponentsAndOriginalReferences() {
        var event = fixture(OUR_TAB);
        var parentAnchor = new ItemStack(Items.STONE);
        var searchLast = new ItemStack(Items.DIRT);
        var parentExisting = namedPaper("same components");
        var suppliedCopy = parentExisting.copy();
        var variant = namedPaper("different components");
        event.accept(parentExisting, PARENT_TAB_ONLY);
        event.accept(parentAnchor, PARENT_TAB_ONLY);
        event.accept(searchLast, SEARCH_TAB_ONLY);

        CreativeTabGroups.insertBefore(event, STONE, PARENT_AND_SEARCH_TABS, suppliedCopy, variant);

        // Duplicate parent membership keeps the original object, while search still receives its copy.
        assertReferences(List.of(parentExisting, variant, parentAnchor), event.getParentEntries());
        assertReferences(List.of(searchLast, suppliedCopy, variant), event.getSearchEntries());
        assertEquals(Component.literal("same components"), suppliedCopy.get(DataComponents.CUSTOM_NAME));
        assertEquals(Component.literal("different components"), variant.get(DataComponents.CUSTOM_NAME));
        assertNotSame(parentExisting, suppliedCopy);
        assertTrue(ItemStack.isSameItemSameComponents(parentExisting, suppliedCopy));
    }

    @Test void singleViewContributionsNeverLeakToTheOtherView() {
        var event = fixture(OUR_TAB);
        var anchor = new ItemStack(Items.STONE);
        var parentOnly = namedPaper("parent only");
        var searchOnly = namedPaper("search only");
        event.accept(anchor);

        CreativeTabGroups.insertBefore(event, STONE, PARENT_TAB_ONLY, parentOnly);
        CreativeTabGroups.insertBefore(event, STONE, SEARCH_TAB_ONLY, searchOnly);

        assertReferences(List.of(parentOnly, anchor), event.getParentEntries());
        assertReferences(List.of(searchOnly, anchor), event.getSearchEntries());
    }

    @Test void componentBearingAnchorIsUsedWithoutReplacingItOrOtherEntries() {
        var event = fixture(OUR_TAB);
        var anchor = new ItemStack(Items.STONE);
        anchor.set(DataComponents.CUSTOM_NAME, Component.literal("changed anchor"));
        var addition = namedPaper("addition");
        event.accept(anchor);
        CreativeTabGroups.insertBefore(event, STONE, PARENT_AND_SEARCH_TABS, addition);
        assertReferences(List.of(addition, anchor), event.getParentEntries());
        assertReferences(List.of(addition, anchor), event.getSearchEntries());
        assertEquals(Component.literal("changed anchor"), anchor.get(DataComponents.CUSTOM_NAME));
    }

    @Test void missingAnchorAppendsAndRepeatedInvocationsSkipEquivalentStacks() {
        var event = fixture(OUR_TAB);
        var first = namedPaper("first");
        var second = namedPaper("second");
        for (int i = 0; i < 3; i++) {
            CreativeTabGroups.insert(event, CreativeTabGroups.Group.CONSOLES,
                    PARENT_AND_SEARCH_TABS, first, first.copy(), second);
        }
        assertReferences(List.of(first, second), event.getParentEntries());
        assertReferences(List.of(first, second), event.getSearchEntries());
        CreativeTabGroups.insert(event, CreativeTabGroups.Group.CONSOLES, Items.APPLE, Items.DIRT);
        assertEquals(List.of(Items.PAPER, Items.PAPER, Items.APPLE, Items.DIRT),
                event.getParentEntries().stream().map(ItemStack::getItem).toList());
    }

    @Test void invalidStackCountsFailBeforeAnyEntriesAreInserted() {
        var event = fixture(OUR_TAB);
        var invalid = new ItemStack(Items.STONE, 2);
        assertThrows(IllegalArgumentException.class, () -> CreativeTabGroups.insert(event,
                CreativeTabGroups.Group.MEDIA, PARENT_AND_SEARCH_TABS, namedPaper("valid"), invalid));
        assertTrue(event.getParentEntries().isEmpty());
        assertTrue(event.getSearchEntries().isEmpty());
    }

    @Test void unrelatedTabsRemainUntouchedByBothHelpers() {
        var event = fixture(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                ResourceLocation.parse("another_mod:fc")));
        var existing = namedPaper("existing");
        event.accept(existing, PARENT_TAB_ONLY);
        CreativeTabGroups.insert(event, CreativeTabGroups.Group.MEDIA, Items.APPLE);
        CreativeTabGroups.insert(event, CreativeTabGroups.Group.MEDIA, SEARCH_TAB_ONLY, namedPaper("new"));
        RetiredCreativeEntries.hide(event, ResourceLocation.parse("minecraft:paper"));
        assertReferences(List.of(existing), event.getParentEntries());
        assertTrue(event.getSearchEntries().isEmpty());
    }

    @Test void retirementRemovesEveryMatchingVariantWithoutReorderingSurvivors() {
        var event = fixture(OUR_TAB);
        var parentRetired = namedPaper("retired parent");
        var searchRetired = namedPaper("retired search");
        var bothRetired = namedPaper("retired both");
        var parentKeep = new ItemStack(Items.DIRT);
        var bothKeep = new ItemStack(Items.APPLE);
        var searchKeep = new ItemStack(Items.STICK);
        event.accept(parentRetired, PARENT_TAB_ONLY);
        event.accept(parentKeep, PARENT_TAB_ONLY);
        event.accept(searchRetired, SEARCH_TAB_ONLY);
        event.accept(bothKeep);
        event.accept(bothRetired);
        event.accept(searchKeep, SEARCH_TAB_ONLY);

        for (int i = 0; i < 2; i++)
            RetiredCreativeEntries.hide(event, ResourceLocation.parse("minecraft:paper"));

        assertReferences(List.of(parentKeep, bothKeep), event.getParentEntries());
        assertReferences(List.of(bothKeep, searchKeep), event.getSearchEntries());
        assertEquals(Component.literal("retired both"), bothRetired.get(DataComponents.CUSTOM_NAME));
        RetiredCreativeEntries.onBuildContents(event);
        assertReferences(List.of(parentKeep, bothKeep), event.getParentEntries());
        assertReferences(List.of(bothKeep, searchKeep), event.getSearchEntries());
    }

    private static ItemStack namedPaper(String name) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static BuildCreativeModeTabContentsEvent fixture(ResourceKey<CreativeModeTab> key) {
        var parent = new InsertableLinkedOpenCustomHashSet<ItemStack>(ItemStackLinkedSet.TYPE_AND_TAG);
        var search = new InsertableLinkedOpenCustomHashSet<ItemStack>(ItemStackLinkedSet.TYPE_AND_TAG);
        var tab = CreativeModeTab.builder().title(Component.literal("test tab"))
                .icon(() -> new ItemStack(Items.STONE)).displayItems((parameters, output) -> {}).build();
        var parameters = new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, RegistryAccess.EMPTY);
        return new BuildCreativeModeTabContentsEvent(tab, key, parameters, parent, search);
    }

    private static void assertReferences(List<ItemStack> expected, Collection<ItemStack> actual) {
        var snapshot = List.copyOf(actual);
        assertEquals(expected.size(), snapshot.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), snapshot.get(i), "entry " + i);
    }
}
