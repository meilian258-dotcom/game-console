package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Pure geometry plus source-level rendering contracts; does not claim an in-game screenshot test. */
class FcMenuLayoutTest {
    @Test void largeWindowsKeepACenteredBoundedPanelInsteadOfStretchingAcrossTheScreen() {
        var layout = FcMenuLayout.library(1920, 1080);
        assertEquals(new FcMenuLayout.Rect(580, 310, 760, 460), layout.panel());
        assertTrue(layout.split());
        assertTrue(FcMenuLayout.library(800, 600).panel().width() < layout.panel().width());
    }

    @Test void screenshotSizedWindowsHaveProportionalMarginsAndMediumLibraryStillSplits() {
        assertEquals(new FcMenuLayout.Rect(31, 19, 450, 239), FcMenuLayout.library(512, 278).panel());
        assertEquals(new FcMenuLayout.Rect(38, 24, 563, 312), FcMenuLayout.library(640, 360).panel());
        assertEquals(new FcMenuLayout.Rect(132, 48, 760, 460), FcMenuLayout.library(1024, 556).panel());
        assertEquals(new FcMenuLayout.Rect(12, 12, 296, 216), FcMenuLayout.library(320, 240).panel());
        assertTrue(FcMenuLayout.library(640, 360).split());
        assertTrue(FcMenuLayout.cartridge(640, 360, false).split());
        assertFalse(FcMenuLayout.cartridge(640, 360, true).split());
    }

    @Test void everySupportedSizeKeepsRowsDetailsAndActionsInsideThePanel() {
        for (int width : new int[]{320, 360, 480, 512, 584, 640, 800, 1024, 1920})
            for (int height : new int[]{240, 256, 278, 320, 360, 480, 556, 1080}) {
                var library = FcMenuLayout.library(width, height);
                for (var layout : List.of(library, FcMenuLayout.cartridge(width, height, false),
                        FcMenuLayout.cartridge(width, height, true))) {
                    assertTrue(layout.supported());
                    assertTrue(new FcMenuLayout.Rect(0, 0, width, height).contains(layout.panel()));
                    assertTrue(layout.panel().contains(layout.list()));
                    assertTrue(layout.rows() >= 1 && layout.rows() <= 12);
                    for (int i = 0; i < layout.rows(); i++) {
                        assertTrue(layout.list().contains(layout.row(i)), width + "x" + height + " row " + i);
                        assertTrue(layout.row(i).width() > 0);
                        if (i > 0) assertFalse(layout.row(i - 1).overlaps(layout.row(i)));
                    }
                    if (layout.details().width() > 0) {
                        assertTrue(layout.panel().contains(layout.details()));
                        assertFalse(layout.list().overlaps(layout.details()));
                    }
                }
                var actions = FcMenuLayout.libraryActions(library);
                assertEquals(5, actions.size());
                for (int i = 0; i < actions.size(); i++) {
                    assertTrue(library.details().contains(actions.get(i)), width + "x" + height + " action " + i);
                    for (int j = 0; j < i; j++) assertFalse(actions.get(i).overlaps(actions.get(j)));
                }
            }
    }

    @Test void wideButShortWindowUsesCompactSideActionsInsteadOfVerticalOverflow() {
        var library = FcMenuLayout.library(1280, 240);
        assertTrue(library.split());
        assertTrue(library.compactSplit());
        assertEquals(5, library.rows());
        for (var action : FcMenuLayout.libraryActions(library)) assertTrue(library.panel().contains(action));
    }

    @Test void common512WindowShowsSixGamesWithoutScalingFontsOrDroppingActions() {
        var library = FcMenuLayout.library(512, 278);
        assertEquals(new FcMenuLayout.Rect(31, 19, 450, 239), library.panel());
        assertTrue(library.split());
        assertTrue(library.compactSplit());
        assertEquals(6, library.rows());
        assertEquals(7, FcMenuLayout.pageCount(40, library.rows()));
        assertEquals(242, library.list().width());
        assertEquals(176, library.details().width());
        for (var action : FcMenuLayout.libraryActions(library)) {
            assertEquals(18, action.height());
            assertTrue(library.details().contains(action));
        }
        for (int i = 0; i < library.rows(); i++) assertEquals(20, library.row(i).height());
        assertFalse(FcMenuLayout.library(320, 240).compactSplit());
        assertFalse(FcMenuLayout.library(320, 240).split());
        assertFalse(FcMenuLayout.cartridge(512, 278, false).compactSplit());
        assertFalse(FcMenuLayout.cartridge(512, 278, true).compactSplit());
    }

    @Test void bothLibraryFooterRowsStayInPanelAndOutsideTheListAtEveryResponsiveSize() {
        for (int width : new int[]{320, 480, 512, 640, 1024, 1920}) for (int height : new int[]{240, 278, 360, 556}) {
            var library = FcMenuLayout.library(width, height);
            var upper = FcMenuLayout.columns(library.panel().x() + 10, FcMenuLayout.libraryUpperFooterY(library), library.panel().width() - 20, 4);
            var lower = FcMenuLayout.columns(library.panel().x() + 10, FcMenuLayout.libraryLowerFooterY(library), library.panel().width() - 20, 5);
            for (var row : List.of(upper, lower)) for (var button : row) {
                assertTrue(library.panel().contains(button));
                assertFalse(library.list().overlaps(button));
                assertFalse(library.details().overlaps(button));
            }
            assertFalse(upper.getFirst().overlaps(lower.getFirst()));
        }
    }

    @Test void smallCartridgeWindowIncludesDirectoriesPagerStatusAndBothCoverActions() {
        for (boolean covers : new boolean[]{false, true}) {
            var layout = FcMenuLayout.cartridge(320, 240, covers);
            var panel = layout.panel();
            assertFalse(layout.split());
            assertEquals(covers ? 1 : 2, layout.rows());
            for (var rect : FcMenuLayout.columns(panel.x() + 10, layout.footerY(), panel.width() - 20, 2))
                assertTrue(panel.contains(rect));
            for (var rect : FcMenuLayout.columns(panel.x() + 10, layout.footerY() + 24, panel.width() - 20, 3))
                assertTrue(panel.contains(rect));
            assertTrue(layout.footerY() + 64 + 9 < panel.bottom());
            if (covers) for (var rect : FcMenuLayout.columns(panel.x() + 10, panel.y() + 78, panel.width() - 20, 2)) {
                assertTrue(panel.contains(rect));
                assertFalse(rect.overlaps(layout.list()));
            }
        }
    }

    @Test void paginationUsesActualRowCapacityAndClampsEmptyOrShrinkingLists() {
        assertTrue(FcMenuLayout.library(800, 600).rows() > FcMenuLayout.library(320, 240).rows());
        assertEquals(1, FcMenuLayout.pageCount(0, 3));
        assertEquals(3, FcMenuLayout.pageCount(7, 3));
        assertEquals(2, FcMenuLayout.clampPage(100, 7, 3));
        assertEquals(0, FcMenuLayout.clampPage(100, 0, 3));
        assertEquals(0, FcMenuLayout.clampPage(-1, 7, 3));
    }

    @Test void footerColumnsHaveNoOverlapsAndUseEveryAvailablePixel() {
        for (int width : new int[]{276, 277, 400, 740}) for (int count = 2; count <= 5; count++) {
            var columns = FcMenuLayout.columns(10, 100, width, count);
            assertEquals(10, columns.getFirst().x());
            assertEquals(10 + width, columns.getLast().right());
            for (int i = 0; i < count; i++) {
                assertTrue(columns.get(i).width() > 0);
                if (i > 0) assertEquals(4, columns.get(i).x() - columns.get(i - 1).right());
            }
        }
    }

    @Test void undersizedWindowsUseExplicitFallbackWithoutChangingGuiScale() {
        assertFalse(FcMenuLayout.library(240, 180).supported());
        assertFalse(FcMenuLayout.cartridge(240, 180, true).supported());
        assertTrue(FcMenuLayout.library(320, 240).supported());
    }

    @Test void backgroundsArePlainPanelsBeforeTextAndNeverApplyFullscreenBlur() throws Exception {
        for (String name : List.of("RomLibraryScreen", "ClientCartridgeEditor")) {
            String source = source(name);
            assertFalse(source.contains("renderBackground("), name);
            assertFalse(source.contains("renderBlurredBackground("), name);
            assertFalse(source.contains("renderTransparentBackground("), name);
            assertFalse(source.contains(".guiScale("), name);
            int render = source.indexOf("public void render(");
            String body = source.substring(render, source.indexOf("super.render(", render));
            assertTrue(body.indexOf("DeviceUi.panel(") >= 0 && body.indexOf("DeviceUi.panel(") < body.indexOf("line(graphics,"), name);
            assertTrue(source.replace(" ", "").contains("graphics.renderTooltip(font,hoveredText"), name);
        }
        assertFalse(source("FcMenuUi").contains("processBlur"));
        assertTrue(source("FcMenuUi").contains("Tooltip.create(text)"));
        assertTrue(source("FcMenuUi").contains("plainSubstrByWidth"));
    }

    @Test void modernUiExemptionNamesOnlyTheFourOwnedFcMenusAndPreservesExistingEntries() throws Exception {
        String compat = source("CartridgeScreenCompat");
        for (String name : List.of("ClientCartridgeEditor", "RomLibraryScreen", "RomRenameScreen", "FcRomDeleteScreen"))
            assertTrue(compat.contains("entries.add(" + name + ".class.getName())"));
        assertTrue(compat.contains("FcMenuState.addNames(entries, configuredEntries)"));
        assertTrue(compat.contains("FcMenuState.addNames(entries, runtime)"));
        int unreadable = compat.indexOf("if (runtime == null)");
        assertTrue(unreadable > 0 && compat.indexOf("return;", unreadable) < compat.indexOf("getMethod(\"loadBlacklist\""));
        assertFalse(source("FcMenuState").contains("getDeclaredFields("));
        assertFalse(source("FcMenuState").contains("setAccessible("));
        assertFalse(compat.contains("entries.add(Screen.class"));
        assertFalse(compat.contains("entries.add(ConfirmScreen.class"));
        assertFalse(compat.contains("mBlurEnabled"));
        assertFalse(compat.contains("tacz"));
    }

    @Test void repeatedResizeKeepsTheSameRomOrCoverIdentityAcrossDifferentPageCapacities() {
        var keys = IntStream.range(0, 30).mapToObj(i -> "sha-" + i).toList();
        var rom = new FcMenuState.PageAnchor();
        rom.focus("sha-17");
        for (int rows : new int[]{6, 1, 12, 3, 8, 1, 6}) {
            assertEquals(17 / rows, rom.page(keys, rows));
            assertEquals("sha-17", rom.key());
        }
        var cover = new FcMenuState.PageAnchor();
        cover.focus("cover17.png");
        assertEquals(1, cover.page(List.of("a.png", "cover17.png"), 1));
        assertEquals("sha-17", rom.key());
        assertEquals(0, cover.page(List.of(), 3));
        assertEquals("cover17.png", cover.key());
        assertEquals(0, cover.page(List.of("cover17.png"), 3));
    }

    @Test void pagerClampsShrinkingCatalogAndAnchorsExplicitPageChanges() {
        var anchor = new FcMenuState.PageAnchor();
        var keys = IntStream.range(0, 11).mapToObj(Integer::toString).toList();
        anchor.move(keys, 3, 1);
        assertEquals("3", anchor.key());
        anchor.move(keys, 3, 100);
        assertEquals("9", anchor.key());
        assertEquals(0, anchor.page(List.of("first"), 2));
        assertEquals("first", anchor.key());
        anchor.move(List.of(), 2, 1);
        assertEquals(0, anchor.page(List.of(), 2));
    }

    @Test void dismissedLateOpenCannotReopenButANewSessionForTheSameHeldCardCan() {
        var dismissed = new FcMenuState.DismissedTokens();
        UUID card = UUID.randomUUID(), old = UUID.randomUUID(), fresh = UUID.randomUUID();
        assertTrue(dismissed.permitsOpen(old, card, 2, card, 2, true));
        dismissed.dismiss(old);
        assertFalse(dismissed.permitsOpen(old, card, 2, card, 2, true));
        assertTrue(dismissed.permitsOpen(fresh, card, 2, card, 2, true));
        assertTrue(dismissed.permitsOpen(fresh, card, 40, card, 40, true));
        assertFalse(dismissed.permitsOpen(fresh, card, 2, card, 3, true));
        assertFalse(dismissed.permitsOpen(fresh, card, 40, card, 2, true));
        assertFalse(dismissed.permitsOpen(fresh, card, 2, UUID.randomUUID(), 2, true));
        assertFalse(dismissed.permitsOpen(fresh, card, 2, null, 2, true));
        assertFalse(dismissed.permitsOpen(fresh, card, 2, card, 2, false));
    }

    @Test void rejectedTokenHistoryIsBoundedAndDuplicateDismissalDoesNotEvictFreshTokens() {
        var dismissed = new FcMenuState.DismissedTokens();
        var tokens = IntStream.range(0, 40).mapToObj(i -> new UUID(0, i)).toList();
        tokens.forEach(dismissed::dismiss);
        assertEquals(32, dismissed.size());
        assertFalse(dismissed.contains(tokens.getFirst()));
        assertTrue(dismissed.contains(tokens.getLast()));
        for (int i = 0; i < 50; i++) dismissed.dismiss(tokens.getLast());
        assertEquals(32, dismissed.size());
        assertTrue(dismissed.contains(tokens.get(8)));
        assertFalse(dismissed.contains(new UUID(1, 0)));
    }

    @Test void openGuardAndClosedBackgroundCallbacksAreWiredToTheActualEditor() throws Exception {
        String editor = source("ClientCartridgeEditor");
        int guard = editor.indexOf("DISMISSED.contains(reply.target().token())");
        assertTrue(guard > 0 && guard < editor.indexOf("MC.setScreen(editor)"));
        assertTrue(editor.contains("DISMISSED.permitsOpen(reply.target().token()"));
        assertTrue(editor.contains("MC.player.getInventory().selected : 40"));
        assertTrue(editor.contains("if (!editor.current()) { editor.onClose(); return; }"));
        assertTrue(editor.contains("DISMISSED.dismiss(target.token())"));
        assertTrue(editor.contains("finally { closed = true; revision++; upload = null; busy = false; pendingPermission = 0; }"));
        assertTrue(editor.contains("if (!current() || revision != task) return;"));
        assertTrue(editor.contains("MC.getConnection() != connection"));
        String refresh = editor.substring(editor.indexOf("editor.serverRoms = reply.catalog()"), editor.indexOf("ItemStack held ="));
        assertFalse(refresh.contains("busy = false"));
        assertFalse(refresh.contains("upload = null"));
        assertTrue(editor.contains("page = pageAnchor().page(pageKeys(), pageSize)"));
    }

    public static final class PublicBlacklist {
        public final List<Object> mBlacklist = new ArrayList<>(List.of("another.mod.RuntimeScreen", String.class));
        public Collection<String> getBlurBlacklist() { return List.of("third.mod.ExemptMenu"); }
    }
    public static final class PrivateBlacklist {
        private final List<String> mBlacklist = List.of("must.not.be.replaced");
    }
    public static final class UnknownBlacklist {
        public List<Object> getBlacklist() { return List.of("preserve.me", new Object()); }
    }

    @Test void publicRuntimeBlacklistSnapshotPreservesForeignNamesAndClassesWithoutMutatingIt() throws Exception {
        var handler = new PublicBlacklist();
        List<Object> original = List.copyOf(handler.mBlacklist);
        List<String> runtime = FcMenuState.publicBlacklist(handler);
        assertNotNull(runtime);
        assertTrue(runtime.containsAll(List.of("another.mod.RuntimeScreen", "third.mod.ExemptMenu", "java.lang.String")));
        var merged = new LinkedHashSet<String>();
        FcMenuState.addNames(merged, List.of("configured.mod.Menu"));
        FcMenuState.addNames(merged, runtime);
        assertTrue(merged.contains("configured.mod.Menu"));
        assertEquals(original, handler.mBlacklist);
        assertThrows(UnsupportedOperationException.class, () -> runtime.add("no"));
    }

    @Test void privateOnlyOrUnknownBlacklistStateFailsClosedInsteadOfReplacingOthersEntries() throws Exception {
        assertNull(FcMenuState.publicBlacklist(new PrivateBlacklist()));
        assertThrows(IllegalArgumentException.class, () -> FcMenuState.publicBlacklist(new UnknownBlacklist()));
    }

    @Test void foldersUseOnlyFixedClientApiAndUiDoesNotScanOrDecodeDuringRendering() throws Exception {
        String library = source("RomLibraryScreen"), cartridge = source("ClientCartridgeEditor");
        assertTrue(library.contains("ClientFcDirectories.openRomDirectory()"));
        assertFalse(library.contains("openFile("));
        assertTrue(cartridge.contains("ClientFcDirectories::openRomDirectory"));
        assertTrue(cartridge.contains("ClientFcDirectories::openCoverDirectory"));
        assertFalse(cartridge.contains("openFile("));
        String ui = cartridge.substring(cartridge.indexOf("@Override protected void init()"), cartridge.indexOf("@Override public boolean isPauseScreen()"));
        assertFalse(ui.contains("Files."));
        assertFalse(ui.contains("new RomRepository("));
        assertFalse(ui.contains("prepareRomDirectory("));
        assertFalse(ui.contains("prepareCoverDirectory("));
    }

    @Test void deletionConfirmationAndCartridgeUploadCancellationGuardsRemainPresent() throws Exception {
        String library = source("RomLibraryScreen"), cartridge = source("ClientCartridgeEditor");
        assertTrue(library.contains("new FcRomDeleteScreen("));
        assertTrue(library.contains("if (confirmed)"));
        assertTrue(library.contains("FcNetwork.deleteRom(blockPos, entry.sha256())"));
        assertTrue(library.contains("minecraft.setScreen(this)"));
        assertTrue(source("FcRomDeleteScreen").contains("extends DeviceConfirmScreen"));
        String confirmation = source("ui/DeviceConfirmScreen");
        assertTrue(confirmation.contains("if(dismiss==null)answer(false)"));
        assertTrue(confirmation.contains("else if(!answered){answered=true;dismiss.run();}"));
        assertTrue(confirmation.contains("if(!answered){answered=true;callback.accept(value);}"));
        assertTrue(cartridge.contains("target.cartridgeId().equals(FcCartridgeData.id(held))"));
        assertTrue(cartridge.contains("slot == target.slot()"));
        assertTrue(cartridge.contains("CartridgeNetwork.CANCEL, target"));
        assertTrue(cartridge.contains("revision == task"));
        assertTrue(cartridge.contains("!busy && !scanning"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client", name + ".java"));
    }

    /** Allows these actual assertions to run with javac/java while other agents edit Minecraft classes. */
    public static void main(String[] args) throws Exception {
        int count = 0;
        var tests = new FcMenuLayoutTest();
        for (var method : FcMenuLayoutTest.class.getDeclaredMethods()) if (method.isAnnotationPresent(Test.class)) {
            method.invoke(tests); count++;
        }
        System.out.println("Passed " + count + " FC menu geometry/source-contract checks (standalone assertion runner).");
    }
}
