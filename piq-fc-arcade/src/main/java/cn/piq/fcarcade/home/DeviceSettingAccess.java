package cn.piq.fcarcade.home;

/** Typed access facts shared by device presenters. Not an authorization token. */
public enum DeviceSettingAccess {
    EDITABLE("管理员关机后可修改；已借手柄无需归还。"),
    ADMIN_REQUIRED("仅管理员可修改设备设置。"),
    BUSY("设备正在开局、运行或保存；结束后可修改，已借手柄无需归还。"),
    DIAGNOSTICS_ONLY("此设备只提供诊断，没有可修改的公共联机设置。"),
    READ_FAILED("读取设备状态失败，请刷新或查看日志。");
    private final String reason;
    DeviceSettingAccess(String reason){this.reason=reason;}
    public String reason(){return reason;}
    public boolean editable(){return this==EDITABLE;}
    public static DeviceSettingAccess resolve(boolean readable,boolean diagnostic,boolean admin,boolean busy){
        return !readable?READ_FAILED:diagnostic?DIAGNOSTICS_ONLY:!admin?ADMIN_REQUIRED:busy?BUSY:EDITABLE;
    }
}
