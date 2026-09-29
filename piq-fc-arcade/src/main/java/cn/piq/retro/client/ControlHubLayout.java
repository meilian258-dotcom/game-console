package cn.piq.retro.client;

/** All twelve actual keys remain visible even at the smallest usable GUI size. */
record ControlHubLayout(ControlPanelLayout panel) {
    int tabs() { return panel.top()+26; }
    int presets() { return tabs()+24; }
    int preview() { return presets()+25; }
    int previewStep() { return Math.min(20, Math.max(11, (details()-preview()-5)/4)); }
    int details() { return panel.footer()-70; }
    int hotkeys() { return panel.footer()-46; }
    int scopeNotice() { return panel.footer()-24; }
    int message() { return panel.footer()-12; }
    int cellWidth() { return (panel.innerWidth()-8)/3; }
    int cellX(int index) { return panel.innerX()+index%3*(cellWidth()+4); }
    int cellY(int index) { return preview()+index/3*previewStep(); }
}
