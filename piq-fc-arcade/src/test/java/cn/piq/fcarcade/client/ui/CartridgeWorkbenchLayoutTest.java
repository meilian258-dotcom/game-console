package cn.piq.fcarcade.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeWorkbenchLayoutTest {
    private List<DeviceLayout.Rect> controls(CartridgeWorkbenchLayout w){
        return List.of(w.name(),w.saveName(),w.players(),w.gamesTab(),w.coversTab(),w.search(),w.refresh(),
                w.romFolder(),w.coverFolder(),w.previous(),w.next(),w.close(),w.clearCover(),w.restoreCover());
    }
    private void check(int width,int height){
        var b=DeviceLayout.browser(width,height,2);var w=CartridgeWorkbenchLayout.of(b);
        assertTrue(b.supported());assertTrue(b.split());assertEquals(14,controls(w).size());
        for(var rect:controls(w)){
            assertTrue(b.panel().contains(rect),width+"x"+height+": "+rect);
            assertTrue(rect.width()>=32);assertEquals(20,rect.height());
        }
        for(var rect:List.of(w.name(),w.saveName(),w.players(),w.gamesTab(),w.coversTab(),w.search(),w.refresh()))
            assertTrue(b.toolbar().contains(rect),rect.toString());
        for(var rect:List.of(w.romFolder(),w.coverFolder(),w.previous(),w.next(),w.close()))
            assertTrue(b.navigation().contains(rect),rect.toString());
        for(var rect:List.of(w.clearCover(),w.restoreCover(),b.primary()))assertTrue(b.details().contains(rect));
        var all=controls(w);
        for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)assertFalse(all.get(i).overlaps(all.get(j)),all.get(i)+" / "+all.get(j));
        assertFalse(w.clearCover().overlaps(b.primary()));assertFalse(w.restoreCover().overlaps(b.primary()));
        assertEquals(w.clearCover().y(),w.restoreCover().y());assertTrue(w.clearCover().bottom()+4<=b.primary().y());
        assertEquals(w.name().y(),w.players().y());assertEquals(w.name().y()+24,w.gamesTab().y());
        assertEquals(w.gamesTab().y(),w.search().y());assertEquals(w.search().y(),w.refresh().y());
        // Minecraft's nine-pixel glyph row still fits above cover actions at 320x240.
        int textTop=b.details().y()+3,textBottom=w.clearCover().y()-2;
        assertTrue(textTop+9<=textBottom);assertTrue(b.primary().bottom()<b.navigation().y());
    }
    @Test void allFourteenControlsStayInsideTheirZonesWithoutOverlapAcrossSupportedSizes(){
        for(int width=320;width<=1920;width+=37)for(int height=240;height<=1080;height+=43)check(width,height);
        for(int[] size:new int[][]{{320,240},{360,240},{512,278},{854,480},{1920,1080}})check(size[0],size[1]);
    }
    @Test void footerOrderAndFullWidthDoNotDependOnActiveTab(){
        var b=DeviceLayout.browser(512,278,2);var w=CartridgeWorkbenchLayout.of(b);
        var footer=List.of(w.romFolder(),w.coverFolder(),w.previous(),w.next(),w.close());
        assertEquals(b.navigation().x(),footer.getFirst().x());assertEquals(b.navigation().right(),footer.getLast().right());
        for(int i=1;i<footer.size();i++)assertEquals(4,footer.get(i).x()-footer.get(i-1).right());
        assertEquals(w,CartridgeWorkbenchLayout.of(b));
    }
    @Test void currentCardSaveModeReusesCoverActionRowWithoutIncreasingPanelOrCoverLayout(){
        for(int width=320;width<=1920;width+=37)for(int height=240;height<=1080;height+=43){
            var b=DeviceLayout.browser(width,height,2);var w=CartridgeWorkbenchLayout.of(b);
            var rect=new DeviceLayout.Rect(w.clearCover().x(),w.clearCover().y(),w.restoreCover().right()-w.clearCover().x(),20);
            assertTrue(b.details().contains(rect));assertFalse(rect.overlaps(b.primary()));assertTrue(rect.width()>=100);
            assertTrue(b.details().y()+3+9<=rect.y()-2);
        }
    }
    @Test void realFcAndSfcEditorsBothConsumeAllFourteenSharedControls()throws Exception{
        var fc=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java"));
        var sfc=Files.readString(Path.of("../piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java"));
        for(String source:List.of(fc,sfc)){
            assertTrue(source.contains("CartridgeWorkbenchLayout.of("));
            for(String name:List.of("name","saveName","players","gamesTab","coversTab","search","refresh","romFolder","coverFolder","previous","next","close","clearCover","restoreCover"))
                assertTrue(source.contains("."+name+"()"),name);
        }
    }
}
