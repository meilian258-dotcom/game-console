package cn.piq.fcarcade.registry;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CreativeTabCatalogTest {
    @Test void obsoleteFiveDisplaysAreHiddenButCurrentSingleAndDualCabinetsRemain() {
        var expected = List.of("famicom_console", "retro_tv", "fc_cartridge", "av_cable",
                "subor_console", "panel_tv_2", "panel_tv_3", "gray_crt_tv", "red_crt_tv", "dual_cabinet", "arcade_coin",
                "fc_cartridge_board", "fc_cartridge_shell", "legacy_fc_arcade", "portrait_cabinet", "cartridge_computer", "tv_remote", "debug_screwdriver", "admin_terminal", "fc_zapper", "zapper_stand", "zapper_stand_cable");
        assertEquals(expected, CreativeTabCatalog.itemPaths(false));
        for (boolean installed : new boolean[]{false, true}) {
            var items = CreativeTabCatalog.itemPaths(installed);
            for (String retired : List.of("fc_arcade", "stream_fc_arcade", "deluxe_fc_arcade",
                    "deluxe_stream_fc_arcade", "leaderboard_panel", "lcd_tv", "wide_lcd_tv", "large_lcd_tv", "vintage_tv")) assertFalse(items.contains(retired), retired);
            assertTrue(items.containsAll(List.of("legacy_fc_arcade", "dual_cabinet")));
        }
    }

    @Test void retiredItemsRetainModelsAndBlockstatesForExistingWorlds() throws Exception {
        for (String id : List.of("fc_arcade", "stream_fc_arcade", "deluxe_fc_arcade",
                "deluxe_stream_fc_arcade", "leaderboard_panel")) {
            for (String prefix : List.of("models/item/", "models/block/", "blockstates/")) {
                try (var input = getClass().getResourceAsStream("/assets/piq_fc_arcade/" + prefix + id + ".json")) {
                    assertNotNull(input, "Creative retirement must not delete saved-world resources: " + prefix + id);
                    assertTrue(input.readAllBytes().length > 0);
                }
            }
        }
    }
    @Test void homeKitIsAlwaysFirst() {
        for (boolean installed : new boolean[]{false, true}) {
            assertEquals(List.of("famicom_console", "retro_tv", "fc_cartridge", "av_cable"),
                    CreativeTabCatalog.itemPaths(installed).subList(0, 4));
        }
    }

    @Test void missingOptionalModDoesNotExposeItsDevices() {
        var items = CreativeTabCatalog.itemPaths(false);
        assertTrue(items.containsAll(List.of("gray_crt_tv","red_crt_tv","panel_tv_2","panel_tv_3")));
        assertTrue(items.stream().noneMatch(id -> id.startsWith("waterframes_")));
    }

    @Test void installedWaterFramesAddsOnlyTheFourKnownDevicesAtEnd() {
        var items = CreativeTabCatalog.itemPaths(true);
        var base = CreativeTabCatalog.itemPaths(false);
        assertEquals(base.size() + 4, items.size());
        assertEquals(base, items.subList(0, base.size()));
        assertEquals(List.of("waterframes_fc_arcade", "waterframes_tv_fc_arcade",
                "waterframes_tv_box_fc_arcade", "waterframes_panel_fc_arcade"), items.subList(base.size(), items.size()));
    }

    @Test void suborAddsAnotherConsoleAfterTheOriginalHomeKitWithoutGivingFreeControllers() {
        for (boolean installed : new boolean[]{false, true}) {
            var items = CreativeTabCatalog.itemPaths(installed);
            assertEquals("subor_console", items.get(4));
            assertEquals("panel_tv_2", items.get(5));
            assertTrue(items.containsAll(List.of("fc_cartridge_board", "fc_cartridge_shell", "gray_crt_tv")));
            assertFalse(items.contains("fc_controller"));
            assertEquals(1, items.stream().filter("famicom_console"::equals).count());
        }
    }

    @Test void noDuplicatesHiddenProxyOrMutableGlobalCatalog() {
        for (boolean installed : new boolean[]{false, true}) {
            var items = CreativeTabCatalog.itemPaths(installed);
            assertEquals(items.size(), new HashSet<>(items).size());
            assertTrue(items.stream().noneMatch(id -> id.contains("part")));
            assertThrows(UnsupportedOperationException.class, () -> items.add("unexpected"));
        }
    }

    @Test void everyCatalogEntryHasAnActualItemModel() throws Exception {
        for (String path : CreativeTabCatalog.itemPaths(true)) {
            try (var input = getClass().getResourceAsStream("/assets/piq_fc_arcade/models/item/" + path + ".json")) {
                assertNotNull(input, "Missing item model: " + path);
                assertTrue(input.readAllBytes().length > 0);
            }
        }
    }
}
