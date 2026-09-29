package cn.piq.sfchome.client;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcSharedDeviceLayoutTest {
    @Test void realSfcBrowserHasVisibleSelectedDetailsAndPrimaryOnEverySupportedSize(){
        for(int w:new int[]{320,360,480,520,640,1280})for(int h:new int[]{240,256,300,400,480,720}){
            var l=DeviceLayout.browser(w,h,2);assertTrue(l.supported());assertTrue(l.panel().contains(l.toolbar()));assertTrue(l.panel().contains(l.list()));assertTrue(l.panel().contains(l.details()));
            assertTrue(l.details().contains(l.primary()));assertFalse(l.list().overlaps(l.details()));assertFalse(l.navigation().overlaps(l.details()));
            for(int i=0;i<l.rows();i++)assertTrue(l.list().contains(l.row(i)),w+"x"+h+" row "+i);
            var work=CartridgeWorkbenchLayout.of(l);
            assertTrue(work.name().width()>=112);assertTrue(work.search().width()>=100);
            var footer=java.util.List.of(work.romFolder(),work.coverFolder(),work.previous(),work.next(),work.close());
            for(int i=0;i<footer.size();i++){
                assertTrue(l.navigation().contains(footer.get(i)));assertTrue(footer.get(i).width()>=48);assertEquals(20,footer.get(i).height());
                for(int j=0;j<i;j++)assertFalse(footer.get(i).overlaps(footer.get(j)));
            }
            assertTrue(l.details().contains(work.clearCover()));assertTrue(l.details().contains(work.restoreCover()));
            assertFalse(work.clearCover().overlaps(work.restoreCover()));assertFalse(work.clearCover().overlaps(l.primary()));
        }
    }
    @Test void allSizesUseTheSameCompactSideBySideLayout(){assertTrue(DeviceLayout.browser(320,240,2).split());assertTrue(DeviceLayout.browser(640,480,2).split());assertEquals(420,DeviceLayout.browser(1280,720,2).panel().width());}
}
