package cn.piq.fcarcade.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeSaveSettingsLayoutTest {
    @Test void fullWidthChoicesAndDescriptionsFitEverySupportedScale(){
        for(int width:new int[]{320,356,427,640,854,1280,2560})
            for(int height:new int[]{240,267,360,480,720,1440}){
                var browser=DeviceLayout.browser(width,height,2);
                assertTrue(browser.supported());
                var layout=new CartridgeSaveSettingsLayout(browser.panel());
                for(int i=0;i<3;i++){
                    var choice=layout.choice(i);
                    assertTrue(choice.width()>=260,"Short modes plus current indicator must fit");
                    assertTrue(choice.x()>layout.panel().x());
                    assertTrue(choice.right()<layout.panel().right());
                    assertTrue(layout.descriptionY(i)+9 < (i<2?layout.choice(i+1).y():layout.back().y()));
                    assertFalse(choice.overlaps(layout.back()));
                }
                assertTrue(layout.back().bottom()<layout.status().y());
                assertTrue(layout.status().bottom()<layout.panel().bottom());
            }
    }
    @Test void rejectsNonexistentChoices(){
        var layout=new CartridgeSaveSettingsLayout(DeviceLayout.browser(640,360,2).panel());
        assertThrows(IllegalArgumentException.class,()->layout.choice(-1));
        assertThrows(IllegalArgumentException.class,()->layout.choice(3));
    }
}
