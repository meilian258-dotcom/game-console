package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GamepadSettingsLayoutTest {
    @Test void bindingRowsStatusPagerAndFooterNeverOverlapFrom320x240Upwards() {
        for (int width : new int[]{320, 427, 640, 854, 1280, 1920}) for (int height : new int[]{240, 256, 360, 480, 720, 1080}) {
            var layout = GamepadSettingsLayout.of(width, height, 12);
            assertTrue(layout.left() >= 0); assertTrue(layout.left() + layout.width() <= width);
            assertTrue(layout.bindingY() + (layout.rows() - 1) * 22 + 20 <= layout.statusY());
            assertTrue(layout.statusY() + 10 <= layout.pagerY());
            assertTrue(layout.pagerY() + 20 <= layout.footerY());
            assertTrue(layout.footerY() + 20 <= height);
            assertTrue(layout.pages() * layout.pageSize() >= 12);
        }
    }
}
