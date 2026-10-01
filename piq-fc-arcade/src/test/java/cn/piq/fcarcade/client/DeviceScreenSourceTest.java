package cn.piq.fcarcade.client;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.client.ClientSourceContracts.*;

/** Self-painted production pages must not repaint/blur behind super.render. */
class DeviceScreenSourceTest {
    private static final List<String> PAGES = List.of(
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ArcadeSaveSlotsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ArcadeSaveCatalogScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ArcadeSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/HomeSyncSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/HomeRuntimeSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/TvRemoteSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/NetworkDiagnosticsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/NetplaySaveScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/FcPerformanceScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ContentCardClient.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/PrivateHomeScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/AdminTerminalScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/CartridgeSaveScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSetupScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSyncSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetServerSettingsScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetMenuScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/LeaderboardPanelScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/RomLibraryScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/RomRenameScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/rom/LocalRomPickerScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/runtime/RuntimeEnvironmentScreen.java",
            "piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/SkinLibraryScreen.java",
            "piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java");

    @Test void baseBackgroundIsFinalAndActuallyEmptyAfterRemovingComments() throws Exception {
        String base = read("piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ui/DeviceScreen.java");
        assertTrue(compact(base).contains("abstractclassDeviceScreenextendsScreen"));
        assertTrue(base.contains("public final void renderBackground("));
        assertEquals("", body(base, "public final void renderBackground("));
        assertFalse(base.contains("renderBlurredBackground"));
        assertFalse(base.contains("super.renderBackground"));
    }

    @TestFactory Stream<DynamicTest> allPagesUseNoOpBackgroundButKeepTheirOwnBackdropAndWidgets() {
        return PAGES.stream().map(path -> DynamicTest.dynamicTest(path.substring(path.lastIndexOf('/') + 1), () -> {
            String source = read(path), code = compact(source);
            assertTrue(deviceSubclass(code));
            String render = body(source, "void render(GuiGraphics");
            int fill = render.indexOf(".fill(0,0,width,height,DeviceUi.BG)");
            int widgets = render.indexOf("super.render(");
            assertTrue(fill >= 0, "Self-painted page must retain its own backdrop");
            assertTrue(widgets > fill, "Widgets are rendered after the page's backdrop");
            assertFalse(source.contains("void renderBackground("));
            assertFalse(render.contains("renderBlurredBackground("));
            assertFalse(render.contains("super.renderBackground("));
        }));
    }

    @Test void catalogCoversEveryCurrentDeviceScreenSubclassAcrossAllThreeMods() throws Exception {
        Set<String> actual = new LinkedHashSet<>();
        var root = workspace();
        for (String project : List.of("piq-fc-arcade", "piq-sfc-home", "piq-native-arcade")) {
            try (var paths = Files.walk(root.resolve(project + "/src/main/java"))) {
                for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = compact(uncomment(Files.readString(path)));
                    if (deviceSubclass(code)) actual.add(root.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(25, PAGES.size());
        assertEquals(Set.copyOf(PAGES), actual);
    }

    @Test void ordinaryConfirmationPagesKeepBackgroundFirstOrderInsteadOfInheritingNoOp() throws Exception {
        String confirmation = read("piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ui/DeviceConfirmScreen.java");
        assertTrue(compact(confirmation).contains("classDeviceConfirmScreenextendsScreen{"));
        String render = body(confirmation, "public void render(GuiGraphics");
        assertTrue(render.indexOf("super.render(") < render.indexOf("g.drawCenteredString("));
    }
    private static boolean deviceSubclass(String code){return code.contains("extendscn.piq.fcarcade.client.ui.DeviceScreen{")
            ||(code.contains("importcn.piq.fcarcade.client.ui.DeviceScreen;")||code.contains("importcn.piq.fcarcade.client.ui.*;"))&&code.contains("extendsDeviceScreen{");}
}
