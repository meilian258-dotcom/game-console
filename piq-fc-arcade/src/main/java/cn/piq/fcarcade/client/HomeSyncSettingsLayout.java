// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client;

/** A compact debug page: target, five modes, advanced flags, status and footer. */
record HomeSyncSettingsLayout(int left, int top, int width, boolean compact) {
    static final int HEIGHT = 232;
    static final int MODE_ROW = 40, MODE_STEP = 18, ADVANCED_ROW = 130,
            STATUS_ROW = 152, HINT_ROW = 184, FOOTER_ROW = 204;
    // A selected retired mode adds one explanatory row; preserve the existing panel and footer.
    static int modeStep(int rows){return rows>5?15:MODE_STEP;}
    static int modeHeight(int rows){return rows>5?14:16;}

    static HomeSyncSettingsLayout fit(int width, int height) {
        int panelWidth = Math.max(1, Math.min(400, width - 24));
        return new HomeSyncSettingsLayout((width - panelWidth) / 2,
                Math.max(8, (height - HEIGHT) / 2), panelWidth, width < 320 || height < 240);
    }
}
