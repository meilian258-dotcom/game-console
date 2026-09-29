package cn.piq.fcarcade.client.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetMenuLayoutTest {
    @Test void allSupportedSizesAndPagesStayInsideWindow(){for(int width:new int[]{320,400,640,1280})for(int height:new int[]{240,250,360,720})for(int total=1;total<=16;total++)for(int page=0;page<18;page++){
        var l=CabinetMenuLayout.create(width,height,total,page);assertTrue(l.supported());assertTrue(l.x()>=0);assertTrue(l.x()+l.width()<=width);
        assertTrue(l.y()>=48);assertTrue(l.count()>0);assertTrue(l.start()+l.count()<=total);
        int lastBottom=l.y()+(l.count()-1)*22+20;assertTrue(lastBottom<=l.browser().list().bottom());assertTrue(lastBottom<l.footerY());assertTrue(l.footerY()+20<=height-4);
    }}
    @Test void resizeClampsOldHighPageInsteadOfShowingEmptyMenu(){var small=CabinetMenuLayout.create(320,240,16,3);var big=CabinetMenuLayout.create(640,720,16,small.page()+8);assertEquals(15/big.rows(),big.page());assertEquals(big.page()*big.rows(),big.start());assertEquals(16-big.start(),big.count());}
    @Test void negativePageAndSingleBackendHaveValidFirstPage(){var l=CabinetMenuLayout.create(320,240,1,-99);assertEquals(0,l.page());assertEquals(0,l.start());assertEquals(1,l.count());}
    @Test void unsupportedWindowHasNoInteractiveMenu(){assertFalse(CabinetMenuLayout.create(319,240,3,0).supported());assertFalse(CabinetMenuLayout.create(320,239,3,0).supported());}
    @Test void malformedBackendCountRejected(){assertThrows(IllegalArgumentException.class,()->CabinetMenuLayout.create(320,240,0,0));assertThrows(IllegalArgumentException.class,()->CabinetMenuLayout.create(320,240,17,0));}
}
