package cn.piq.sfchome.client;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcCardLibraryTest {
    private List<SfcCardLibrary.Row> rows(int count){return IntStream.range(0,count).mapToObj(i->SfcCardLibrary.Row.server("hash"+i,String.format(Locale.ROOT,"game%03d.sfc",i),32768)).toList();}
    @Test void localAndServerWithSameNameRemainDistinct(){var s=new SfcCardLibrary("");s.server(List.of(SfcCardLibrary.Row.server("hash","game.sfc",32768)),"");s.local(List.of(SfcCardLibrary.Row.local(Path.of("game.sfc"),"game.sfc",32768)));assertEquals(2,s.filtered().size());assertFalse(s.filtered().get(0).local());assertTrue(s.filtered().get(1).local());assertNotEquals(s.filtered().get(0).key(),s.filtered().get(1).key());}
    @Test void currentCartridgeSelectsServerHashNotFileName(){var s=new SfcCardLibrary("old");s.server(rows(3),"hash2");assertEquals("hash2",s.selected().hash());assertEquals("old",s.title());s.select(s.selected());assertEquals("old",s.title());assertFalse(s.titleDirty());}
    @Test void uneditedOldNameChangesToNewServerOrLocalFile(){var s=new SfcCardLibrary("旧游戏名");s.server(rows(3),"hash0");s.select(s.filtered().get(1));assertEquals("game001.sfc",s.title());assertFalse(s.titleDirty());var local=SfcCardLibrary.Row.local(Path.of("新游戏.smc"),"新游戏.smc",32768);s.local(List.of(local));s.select(local);assertEquals("新游戏.smc",s.title());assertFalse(s.titleDirty());}
    @Test void manuallyChangedNameIsRetainedAcrossMultipleSelections(){var s=new SfcCardLibrary("旧游戏名");s.server(rows(3),"hash0");s.title("手改名称");s.select(s.filtered().get(1));s.select(s.filtered().get(2));assertEquals("手改名称",s.title());assertTrue(s.titleDirty());}
    @Test void programmaticTitleSyncNeverMarksDirtyIncludingLongNames(){var s=new SfcCardLibrary("旧游戏名");s.server(rows(3),"hash0");s.title(s.title());assertFalse(s.titleDirty());s.select(s.filtered().get(1));s.title(s.title());assertFalse(s.titleDirty());String name="长".repeat(160)+".sfc";var local=SfcCardLibrary.Row.local(Path.of(name),name,32768);s.local(List.of(local));s.select(local);assertEquals(128,s.title().length());s.title(s.title());assertFalse(s.titleDirty());s.select(s.filtered().get(2));assertEquals("game002.sfc",s.title());}
    @Test void draftAndStableSelectionSurviveRefresh(){var s=new SfcCardLibrary("old");s.server(rows(3),"hash0");s.title("我的名称草稿");s.select(s.filtered().get(2));s.server(rows(5),"hash0");s.local(List.of());assertEquals("我的名称草稿",s.title());assertEquals("hash2",s.selected().hash());}
    @Test void searchMatchesChineseAndIsCaseInsensitive(){var s=new SfcCardLibrary("");s.server(List.of(SfcCardLibrary.Row.server("a","马里奥.SFC",32768),SfcCardLibrary.Row.server("b","ZELDA.smc",32768)),"");s.query("马里");assertEquals(1,s.filtered().size());s.query(" zelda ");assertEquals("ZELDA.smc",s.filtered().getFirst().name());s.query("missing");assertTrue(s.visible(3).isEmpty());assertEquals(1,s.pages(3));}
    @Test void pagingRetainsAnchorAcrossGuiResizeAndRefresh(){var s=new SfcCardLibrary("");s.server(rows(40),"");s.turn(3,4);assertEquals("hash12",s.visible(4).getFirst().hash());assertTrue(s.visible(5).stream().anyMatch(r->r.hash().equals("hash12")));assertEquals("hash12",s.visible(4).getFirst().hash());s.server(rows(45),"");assertEquals(3,s.page(4));}
    @Test void queryChangeStartsAtFirstPageWithoutLosingDraft(){var s=new SfcCardLibrary("draft");s.server(rows(40),"");s.turn(4,4);s.query("game0");assertEquals(0,s.page(4));assertEquals("draft",s.title());}
    @Test void disappearingSelectedFileClearsSelectionAndClampsPage(){var s=new SfcCardLibrary("");s.server(rows(20),"hash19");s.turn(7,2);s.server(rows(3),"");assertNull(s.selected());assertEquals(1,s.page(2));s.server(List.of(),"");assertEquals(0,s.page(2));assertTrue(s.visible(2).isEmpty());}
}
