package cn.piq.fcarcade.client;

/** Shared coordinates keep the TV-only page and its status area non-overlapping. */
record TvRemoteSettingsLayout(int left, int top, int width, boolean compact) {
    static final int HEIGHT = 204;
    static final int DISPLAY_ROW = 43, SOUND_ROW = 69, VOLUME_ROW = 95,
            INPUT_ROW = 121, STATUS_ROW = 147, FOOTER_ROW = 178;

    static TvRemoteSettingsLayout fit(int width, int height) {
        int panelWidth = Math.max(1, Math.min(380, width - 20));
        return new TvRemoteSettingsLayout((width - panelWidth) / 2,
                Math.max(4, (height - HEIGHT) / 2), panelWidth, width < 320 || height < 240);
    }
}
