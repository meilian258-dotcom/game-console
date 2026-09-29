package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSyncSettingsLayoutTest {
    @Test void debugControlsStatusAndHintsFitWithoutOverlap() {
        for (int width : new int[]{320, 400, 854, 1920}) {
            for (int height : new int[]{240, 270, 480, 1080}) {
                var layout=HomeSyncSettingsLayout.fit(width,height);
                assertFalse(layout.compact());
                assertTrue(layout.left()>=0);
                assertTrue(layout.left()+layout.width()<=width);
                assertTrue(layout.top()+HomeSyncSettingsLayout.HEIGHT<=height);
                assertTrue(HomeSyncSettingsLayout.MODE_ROW+4*HomeSyncSettingsLayout.MODE_STEP+16<=HomeSyncSettingsLayout.ADVANCED_ROW);
                assertTrue(HomeSyncSettingsLayout.ADVANCED_ROW+20<=HomeSyncSettingsLayout.STATUS_ROW);
                assertTrue(HomeSyncSettingsLayout.STATUS_ROW+3*10<=HomeSyncSettingsLayout.HINT_ROW);
                assertTrue(HomeSyncSettingsLayout.HINT_ROW+2*9<=HomeSyncSettingsLayout.FOOTER_ROW);
                assertTrue(HomeSyncSettingsLayout.FOOTER_ROW+20<=HomeSyncSettingsLayout.HEIGHT);
            }
        }
    }
    @Test void undersizedScreenFallsBackToClosingWithoutNegativeWidths() {
        assertTrue(HomeSyncSettingsLayout.fit(319,240).compact());
        assertTrue(HomeSyncSettingsLayout.fit(320,239).compact());
        assertTrue(HomeSyncSettingsLayout.fit(1,1).width()>0);
    }
}
