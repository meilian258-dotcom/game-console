package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlPanelLayoutTest {
    @Test void centeredAndBoundedAtEveryGuiSizeWithoutKeyboardHubOverlap() {
        for(int width=320;width<=1920;width+=37)for(int height=240;height<=1080;height+=7){
            var box=ControlPanelLayout.of(width,height);
            assertTrue(box.usable());assertTrue(box.width()<=440);assertTrue(box.height()<=284);
            assertEquals(width/2,(box.left()+box.width()/2),1);
            assertTrue(box.left()>=0&&box.top()>=0);
            assertTrue(box.left()+box.width()<=width&&box.top()+box.height()<=height);
            var hub=new ControlHubLayout(box);
            assertTrue(hub.tabs()+20<=hub.presets());
            assertTrue(hub.presets()+20<=hub.preview());
            assertTrue(hub.cellY(11)+9<hub.details());
            assertTrue(hub.details()+20<hub.hotkeys());
            assertTrue(hub.hotkeys()+20<box.footer()-14);
            assertTrue(hub.hotkeys()+20<hub.scopeNotice());
            assertTrue(hub.scopeNotice()+9<hub.message());
            assertTrue(hub.message()+9<box.footer());
            for(int i=0;i<12;i++){
                assertTrue(hub.cellX(i)>=box.innerX());
                assertTrue(hub.cellX(i)+hub.cellWidth()<=box.innerX()+box.innerWidth());
            }
            assertTrue(box.footer()+20<box.top()+box.height());
        }
    }
    @Test void deviceOverviewAndMappingAreSeparatedAndNeverOverlapFooter() {
        for(int height=240;height<=1080;height++){
            var box=ControlPanelLayout.of(640,height);int base=box.top()+(box.compact()?34:44);
            assertTrue(base+96+20<=base+121);
            assertTrue(base+121+(box.compact()?22:48)<=box.footer()-13);
            int mappingRows=Math.max(2,Math.min(6,(box.height()-128)/22));
            assertTrue(box.top()+46+(mappingRows-1)*22+20<=box.footer()-40);
            int keyboardRows=Math.max(2,Math.min(6,(box.height()-112)/24));
            assertTrue(box.top()+45+(keyboardRows-1)*24+20<=box.footer()-38);
            int deviceRows=Math.max(1,(box.height()-146)/24);
            assertTrue(box.top()+72+(deviceRows-1)*24+22<=box.footer()-38);
        }
    }
    @Test void nativeActionLabelsKeepNesAndTwelveBitSystemsDistinct() {
        assertArrayEquals(new String[]{"A","B","选择","开始","上","下","左","右"},ControlLabels.nativeButtons("NES"));
        var sfc=ControlLabels.nativeButtons("SFC");assertEquals("B",sfc[0]);assertEquals("A",sfc[8]);
        var arcade=ControlLabels.nativeButtons("ARCADE");assertEquals("投币",arcade[2]);assertEquals("按键 3",arcade[8]);
    }
}
