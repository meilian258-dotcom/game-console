package cn.piq.fcarcade.client.ui;

/** Full-width choices keep the active save mode visible at every supported GUI scale. */
public record CartridgeSaveSettingsLayout(DeviceLayout.Rect panel) {
    public DeviceLayout.Rect choice(int index) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("choice");
        return new DeviceLayout.Rect(panel.x()+10, panel.y()+42+index*36, panel.width()-20, 20);
    }
    public int descriptionY(int index) { return choice(index).bottom()+3; }
    public DeviceLayout.Rect back() {
        return new DeviceLayout.Rect(panel.x()+10,panel.bottom()-43,panel.width()-20,20);
    }
    public DeviceLayout.Rect status() {
        return new DeviceLayout.Rect(panel.x()+10,panel.bottom()-18,panel.width()-20,12);
    }
}
