package cn.piq.fcarcade.client.runtime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimePanelLayoutTest {
    @Test void fitsEverySupportedGuiSize(){
        for(int width=320;width<1500;width+=13)for(int height=240;height<1000;height+=17){
            var b=RuntimePanelLayout.of(width,height);
            assertTrue(b.supported());assertTrue(b.x()>=0&&b.y()>=0);
            assertTrue(b.x()+b.width()<=width&&b.y()+b.height()<=height);
            assertTrue(b.row(6)+11<=b.footer());
            assertTrue(b.buttonX(2)+b.buttonWidth()<=b.x()+b.width()-10);
            assertTrue(b.footer()+44<=b.y()+b.height()-10);
        }
    }
    @Test void tinyWindowsOfferNoClippedForm(){assertFalse(RuntimePanelLayout.of(280,180).supported());}
}
