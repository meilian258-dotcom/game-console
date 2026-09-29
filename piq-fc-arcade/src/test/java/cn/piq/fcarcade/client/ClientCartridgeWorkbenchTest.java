package cn.piq.fcarcade.client;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real nested pure matcher plus screen wiring; does not initialize Minecraft or open a screen. */
class ClientCartridgeWorkbenchTest {
    private String source()throws Exception{
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java"));
    }
    private Method matcher()throws Exception{
        var method=Class.forName("cn.piq.fcarcade.client.ClientCartridgeEditor$Search").getDeclaredMethod("matches",String.class,String.class);
        method.setAccessible(true);return method;
    }
    private boolean matches(Method matcher,String name,String query)throws Exception{
        return (boolean)matcher.invoke(null,name,query);
    }
    @Test void actualMatcherIsCaseInsensitiveLiteralAndLocaleStable()throws Exception{
        var matcher=matcher();var original=Locale.getDefault();
        try{
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(matches(matcher,"PIQ-INFINITY.NES"," infinity "));
            assertTrue(matches(matcher,"超级马里奥.nes","马里奥"));
            assertTrue(matches(matcher,"Same Game (USA).NES","GAME"));
            assertTrue(matches(matcher,"任何名称.png","  "));
            assertFalse(matches(matcher,"Contra.nes",".*"));
            assertTrue(matches(matcher,"literal.*.png",".*"));
            assertFalse(matches(matcher,"Contra.nes","Contra 2"));
        }finally{Locale.setDefault(original);}
    }
    @Test void matcherDoesNotDependOnMinecraftOrOuterScreenInitialization()throws Exception{
        var type=matcher().getDeclaringClass();
        try(var stream=type.getResourceAsStream("ClientCartridgeEditor$Search.class")){
            assertNotNull(stream);String constants=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("net/minecraft/"));assertFalse(constants.contains("CartridgeNetwork"));
        }
    }
    @Test void bothRowsAndFiveFooterActionsUseOneSharedWorkbenchGeometry()throws Exception{
        var s=source();assertTrue(s.contains("CartridgeWorkbenchLayout.of(menu)"));
        for(String rect:List.of("name","saveName","players","gamesTab","coversTab","search","refresh","romFolder","coverFolder","previous","next","close","clearCover","restoreCover"))
            assertTrue(s.contains("workbench."+rect+"()"),rect);
        assertTrue(s.contains("button(\"ROM 目录\",workbench.romFolder(),ClientFcDirectories::openRomDirectory,true)"));
        assertTrue(s.contains("button(\"封面目录\",workbench.coverFolder(),ClientFcDirectories::openCoverDirectory,true)"));
        assertTrue(s.contains("\"当前卡带 · \"+current"));assertTrue(s.contains("\"待应用封面\":\"待写入游戏\""));
        assertTrue(s.contains("\"名称：\"+chosen"));assertTrue(s.contains("\"来源：\"+origin"));
    }
    @Test void filteringKeepsRomIdentityAndDoesNotExposeHiddenSelectionForWrite()throws Exception{
        var s=source();
        assertTrue(s.contains("entries.stream().filter(entry -> Search.matches(entry.name, query)).toList()"));
        assertTrue(s.contains("coverEntries().stream().filter(cover -> Search.matches(cover.fileName, query)).toList()"));
        assertTrue(s.contains("filteredRoms.stream().filter(entry->entry.hash.equals(chosenRomSha))"));
        assertTrue(s.contains("filteredCovers.stream().filter(cover->cover.fileName.equals(chosenCoverFile))"));
        assertTrue(s.contains("filteredRoms.stream().map(Entry::hash).toList()"));
        assertTrue(s.contains("page = pageAnchor().page(pageKeys(), pageSize)"));
        String callback=s.substring(s.indexOf("searchBox.setResponder("),s.indexOf("button(\"刷新\",workbench.refresh()"));
        assertTrue(callback.contains("pageAnchor().reset()"));
        for(String forbidden:List.of("chosenRomSha=","chosenCoverFile=","draftTitle=","titleDirty=","send(","write(","scanLocal(","Files."))
            assertFalse(callback.contains(forbidden),forbidden);
    }
    @Test void rebuildPreservesSearchCursorAndNameDraftWithoutInventingDirtyEdits()throws Exception{
        var s=source();
        assertTrue(s.indexOf("if (titleBox != null) draftTitle = titleBox.getValue()")<s.indexOf("clearWidgets()"));
        assertTrue(s.indexOf("if (searchBox != null) query = searchBox.getValue()")<s.indexOf("clearWidgets()"));
        assertTrue(s.contains("setInitialFocus(searchBox);searchBox.moveCursorTo(searchCursor,false)"));
        assertTrue(s.contains("setInitialFocus(titleBox);titleBox.moveCursorTo(titleCursor,false)"));
        String rebuild=s.substring(s.indexOf("@Override protected void rebuildWidgets()"),s.indexOf("private Button button("));
        assertTrue(rebuild.indexOf("searchBox.isFocused()")<rebuild.indexOf("super.rebuildWidgets()"));
        assertTrue(rebuild.indexOf("super.rebuildWidgets()")<rebuild.indexOf("setInitialFocus(searchBox)"));
        assertTrue(s.indexOf("titleBox.setValue(draftTitle)")<s.indexOf("titleBox.setResponder("));
        assertTrue(s.indexOf("searchBox.setValue(query)")<s.indexOf("searchBox.setResponder("));
        assertTrue(s.contains("if(!titleDirty)"));
    }
    @Test void clearRestoreRemainBoundToWrittenCardNotUnwrittenGameDraft()throws Exception{
        var s=source();assertTrue(s.contains("()->writeCover(\"\")"));assertTrue(s.contains("()->writeCover(originalCover)"));
        String action=s.substring(s.indexOf("private void writeCover("),s.indexOf("private void select("));
        assertTrue(action.contains("romSha, cover, currentCardTitle"));assertFalse(action.contains("draftTitle"));
        assertTrue(action.contains("if (!current() || busy) return"));
        assertTrue(s.contains("finally { closed = true; revision++; upload = null; busy = false; pendingPermission = 0; }"));
        assertTrue(s.contains("if (!current() || revision != task) return;"));
        assertTrue(s.contains("MC.getConnection() != connection"));
    }

    @Test void existingServerCoverIsDistinctFromLocalUploadAndPreservesCurrentCard()throws Exception{
        var s=source();
        assertTrue(s.contains("serverCovers = reply.covers()"));
        assertTrue(s.contains("if(has(PlayerContentPolicy.SERVER_COVER_USE))for(String sha:serverCovers)"));
        assertTrue(s.contains("if(chosen.server())writeCover(chosen.hash);else uploadCover(chosen)"));
        assertTrue(s.contains("has(cover.server()?PlayerContentPolicy.SERVER_COVER_USE:PlayerContentPolicy.COVER_UPLOAD)"));
        assertTrue(s.contains("has(selected.server?PlayerContentPolicy.SERVER_ROM_USE:PlayerContentPolicy.ROM_UPLOAD)"));
        assertTrue(s.contains("if(has(PlayerContentPolicy.SERVER_ROM_USE))write(entry.hash, coverSha)"));
    }
}
