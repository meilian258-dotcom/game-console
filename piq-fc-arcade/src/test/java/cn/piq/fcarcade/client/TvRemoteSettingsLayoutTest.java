package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TvRemoteSettingsLayoutTest {
    @Test void supportedSizesKeepAllRowsInsidePanelWithoutOverlappingStatus() {
        for (int width : new int[]{320, 400, 854, 1920}) {
            for (int height : new int[]{240, 270, 480, 1080}) {
                var layout = TvRemoteSettingsLayout.fit(width, height);
                assertFalse(layout.compact());
                assertTrue(layout.left() >= 0);
                assertTrue(layout.left() + layout.width() <= width);
                assertTrue(layout.top() + TvRemoteSettingsLayout.HEIGHT <= height);
                assertTrue(TvRemoteSettingsLayout.DISPLAY_ROW + 20 <= TvRemoteSettingsLayout.SOUND_ROW);
                assertTrue(TvRemoteSettingsLayout.SOUND_ROW + 20 <= TvRemoteSettingsLayout.VOLUME_ROW);
                assertTrue(TvRemoteSettingsLayout.VOLUME_ROW + 20 <= TvRemoteSettingsLayout.INPUT_ROW);
                assertTrue(TvRemoteSettingsLayout.INPUT_ROW + 20 <= TvRemoteSettingsLayout.STATUS_ROW);
                assertTrue(TvRemoteSettingsLayout.STATUS_ROW + 3 * 9 <= TvRemoteSettingsLayout.FOOTER_ROW);
                assertTrue(TvRemoteSettingsLayout.FOOTER_ROW + 20 <= TvRemoteSettingsLayout.HEIGHT);
            }
        }
    }
    @Test void smallWindowsUseTheCloseOnlyFallback() {
        assertTrue(TvRemoteSettingsLayout.fit(319, 240).compact());
        assertTrue(TvRemoteSettingsLayout.fit(320, 239).compact());
        assertTrue(TvRemoteSettingsLayout.fit(1, 1).width() > 0);
    }
}
