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

/** Exercises real NeoForge collections and ItemStack component identity without mutating global registries. */
class CreativeHardwareOrderEventTest {
    private static final ResourceKey<CreativeModeTab> OUR_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.parse("piq_fc_arcade:fc"));

    @BeforeAll static void bootstrap() {
        if (LoadingModList.get() == null)
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void preservesVisibilityComponentsOriginalReferencesAndUnknownRelativeOrder() {
        var fixture = fixture(OUR_TAB);
        var parentOnly = namedPaper("parent only");
        var searchOnly = namedPaper("search only");
        var bothFirst = namedPaper("both first");
        var bothSecond = namedPaper("both second");
        fixture.event.accept(parentOnly, PARENT_TAB_ONLY);
        fixture.event.accept(bothFirst, PARENT_AND_SEARCH_TABS);
        fixture.event.accept(searchOnly, SEARCH_TAB_ONLY);
        fixture.event.accept(bothSecond, PARENT_AND_SEARCH_TABS);
        assertNotEquals(parentOnly.getComponents(), searchOnly.getComponents());

        CreativeHardwareOrder.onBuildContents(fixture.event);

        assertReferences(List.of(parentOnly, bothFirst, bothSecond), fixture.event.getParentEntries());
        assertReferences(List.of(bothFirst, searchOnly, bothSecond), fixture.event.getSearchEntries());
        assertFalse(fixture.event.getSearchEntries().contains(parentOnly));
        assertFalse(fixture.event.getParentEntries().contains(searchOnly));
        assertEquals(Component.literal("parent only"), parentOnly.get(DataComponents.CUSTOM_NAME));
        assertEquals(Component.literal("search only"), searchOnly.get(DataComponents.CUSTOM_NAME));
        assertEquals(1, bothFirst.getCount());
        assertEquals(1, bothSecond.getCount());
        // The original event-owned sets remain usable; the handler does not replace them with an immutable List.
        assertReferences(List.of(parentOnly, bothFirst, bothSecond), fixture.parent);
        assertReferences(List.of(bothFirst, searchOnly, bothSecond), fixture.search);
    }

    @Test void independentlyAddingRemovingAndReaddingTheSameStackPreservesBothMemberships() {
        var fixture = fixture(OUR_TAB);
        var shared = namedPaper("shared stack");
        fixture.event.accept(shared, PARENT_TAB_ONLY);
        fixture.event.accept(shared, SEARCH_TAB_ONLY);
        assertReferences(List.of(shared), fixture.parent);
        assertReferences(List.of(shared), fixture.search);

        fixture.event.remove(shared, PARENT_TAB_ONLY);
        assertTrue(fixture.parent.isEmpty());
        assertReferences(List.of(shared), fixture.search);
        fixture.event.accept(shared, PARENT_TAB_ONLY);
        CreativeHardwareOrder.onBuildContents(fixture.event);
        assertReferences(List.of(shared), fixture.parent);
        assertReferences(List.of(shared), fixture.search);

        fixture.event.remove(shared, SEARCH_TAB_ONLY);
        assertReferences(List.of(shared), fixture.parent);
        assertTrue(fixture.search.isEmpty());
        fixture.event.accept(shared, SEARCH_TAB_ONLY);
        CreativeHardwareOrder.onBuildContents(fixture.event);
        assertReferences(List.of(shared), fixture.parent);
        assertReferences(List.of(shared), fixture.search);
    }

    @Test void equivalentButDistinctStacksKeepTheirOwnParentAndSearchReferences() {
        var fixture = fixture(OUR_TAB);
        var parentStack = namedPaper("same components");
        var searchStack = parentStack.copy();
        assertNotSame(parentStack, searchStack);
        assertTrue(ItemStack.isSameItemSameComponents(parentStack, searchStack));
        fixture.event.accept(parentStack, PARENT_TAB_ONLY);
        fixture.event.accept(searchStack, SEARCH_TAB_ONLY);

        CreativeHardwareOrder.onBuildContents(fixture.event);

        assertReferences(List.of(parentStack), fixture.parent);
        assertReferences(List.of(searchStack), fixture.search);
        // Public API retains its component-based uniqueness contract after reordering.
        assertThrows(IllegalArgumentException.class, () -> fixture.event.accept(parentStack.copy(), PARENT_TAB_ONLY));
        assertThrows(IllegalArgumentException.class, () -> fixture.event.accept(searchStack.copy(), SEARCH_TAB_ONLY));
    }

    @Test void repeatedBuildCallbacksAreIdempotentAndLeavePublicMutationApisUsable() {
        var fixture = fixture(OUR_TAB);
        var first = new ItemStack(Items.STONE);
        var second = new ItemStack(Items.DIRT);
        var later = new ItemStack(Items.APPLE);
        fixture.event.accept(first, PARENT_AND_SEARCH_TABS);
        fixture.event.accept(second, PARENT_AND_SEARCH_TABS);

        for (int i = 0; i < 3; i++) CreativeHardwareOrder.onBuildContents(fixture.event);
        assertReferences(List.of(first, second), fixture.parent);
        assertReferences(List.of(first, second), fixture.search);
        fixture.event.insertAfter(first, later, PARENT_AND_SEARCH_TABS);
        CreativeHardwareOrder.onBuildContents(fixture.event);
        assertReferences(List.of(first, later, second), fixture.parent);
        assertReferences(List.of(first, later, second), fixture.search);
        fixture.event.remove(later, PARENT_AND_SEARCH_TABS);
        assertReferences(List.of(first, second), fixture.parent);
        assertReferences(List.of(first, second), fixture.search);
    }

    @Test void emptyTabsAndOtherNamespacesAreLeftIntact() {
        var empty = fixture(OUR_TAB);
        CreativeHardwareOrder.onBuildContents(empty.event);
        assertTrue(empty.parent.isEmpty());
        assertTrue(empty.search.isEmpty());

        var other = fixture(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                ResourceLocation.parse("another_mod:fc")));
        var first = namedPaper("other parent");
        var second = namedPaper("other search");
        other.event.accept(first, PARENT_TAB_ONLY);
        other.event.accept(second, SEARCH_TAB_ONLY);
        CreativeHardwareOrder.onBuildContents(other.event);
        assertReferences(List.of(first), other.parent);
        assertReferences(List.of(second), other.search);
    }

    @Test void neoForgeViewsAreReadOnlySoAllChangesMustUseTheEventApi() {
        var fixture = fixture(OUR_TAB);
        var stack = new ItemStack(Items.STONE);
        fixture.event.accept(stack, PARENT_AND_SEARCH_TABS);
        assertThrows(UnsupportedOperationException.class, () -> fixture.event.getParentEntries().clear());
        assertThrows(UnsupportedOperationException.class, () -> fixture.event.getSearchEntries().remove(stack));
        assertDoesNotThrow(() -> CreativeHardwareOrder.onBuildContents(fixture.event));
        assertReferences(List.of(stack), fixture.parent);
        assertReferences(List.of(stack), fixture.search);
    }

    private static ItemStack namedPaper(String name) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static Fixture fixture(ResourceKey<CreativeModeTab> key) {
        var parent = new InsertableLinkedOpenCustomHashSet<ItemStack>(ItemStackLinkedSet.TYPE_AND_TAG);
        var search = new InsertableLinkedOpenCustomHashSet<ItemStack>(ItemStackLinkedSet.TYPE_AND_TAG);
        var tab = CreativeModeTab.builder().title(Component.literal("test tab"))
                .icon(() -> new ItemStack(Items.STONE)).displayItems((parameters, output) -> {}).build();
        var parameters = new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, RegistryAccess.EMPTY);
        return new Fixture(new BuildCreativeModeTabContentsEvent(tab, key, parameters, parent, search), parent, search);
    }

    private static void assertReferences(List<ItemStack> expected, Collection<ItemStack> actual) {
        var snapshot = List.copyOf(actual);
        assertEquals(expected.size(), snapshot.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), snapshot.get(i), "entry " + i);
    }

    private record Fixture(BuildCreativeModeTabContentsEvent event,
                           InsertableLinkedOpenCustomHashSet<ItemStack> parent,
                           InsertableLinkedOpenCustomHashSet<ItemStack> search) {}
}
