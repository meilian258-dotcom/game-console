package cn.piq.fcarcade.client.rom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalRomPickerLayoutTest {
    @Test void responsiveRowsNeverOverlapControls(){
        for(int[] size:new int[][]{{320,240},{427,240},{512,278},{640,360},{854,480},{1920,1080}})
            for(int count:new int[]{0,1,3,5,512})for(int page:new int[]{-1,0,1,999}){
                var l=LocalRomPickerLayout.create(size[0],size[1],count,page);assertTrue(l.supported());
                assertTrue(l.panel().x()>=0&&l.panel().y()>=0);assertTrue(l.panel().right()<=size[0]&&l.panel().bottom()<=size[1]);
                assertTrue(l.toolbar().bottom()<=l.search().y());assertTrue(l.search().bottom()<=l.list().y());
                for(int r=0;r<l.rows();r++)assertTrue(l.row(r).bottom()<=l.list().bottom());
                assertTrue(l.footer().bottom()<l.status().y());assertTrue(l.status().bottom()<=l.panel().bottom());assertTrue(l.page()>=0&&l.page()<l.pages());
                assertEquals(l.page()*l.rows(),l.start());assertTrue(l.rows()>=1);
            }
    }
    @Test void shrinkingAndEmptyRefreshClampPage(){assertEquals(0,LocalRomPickerLayout.create(320,240,0,50).page());assertEquals(0,LocalRomPickerLayout.create(512,278,1,4).page());}
    @Test void undersizeIsExplicitlyUnsupported(){assertFalse(LocalRomPickerLayout.create(319,240,2,0).supported());assertFalse(LocalRomPickerLayout.create(320,239,2,0).supported());}
}
