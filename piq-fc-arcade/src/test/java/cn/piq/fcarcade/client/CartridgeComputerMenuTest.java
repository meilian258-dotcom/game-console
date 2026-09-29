package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeComputerMenuTest {
    @Test void fourControlsFitWithoutPushingTheFileListDown() {
        for (int[] size : new int[][]{{320,240},{360,240},{480,270},{640,360},{854,480},{1280,720},{2560,1440}}) {
            for (boolean covers : new boolean[]{false,true}) {
                var menu = FcMenuLayout.cartridge(size[0],size[1],covers);
                var tabs = FcMenuLayout.cartridgeComputerTabs(menu);
                assertTrue(menu.supported()); assertEquals(4,tabs.size());
                for (int i=0;i<tabs.size();i++) {
                    var tab=tabs.get(i);
                    assertTrue(menu.panel().contains(tab));
                    assertTrue(tab.width()>=60, "A short Chinese mode label must remain usable");
                    assertTrue(tab.bottom()<=menu.list().y());
                    for(int j=i+1;j<tabs.size();j++) assertFalse(tab.overlaps(tabs.get(j)));
                }
                assertTrue(menu.panel().contains(menu.row(0)));
                assertTrue(menu.row(menu.rows()-1).bottom()<=menu.footerY());
            }
        }
    }
    @Test void unsupportedWindowsKeepTheExistingSafeCloseFallback() {
        for (int[] size:new int[][]{{319,240},{320,239},{160,100}})
            assertFalse(FcMenuLayout.cartridge(size[0],size[1],false).supported());
    }
}
