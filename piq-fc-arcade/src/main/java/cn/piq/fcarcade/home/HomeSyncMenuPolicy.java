package cn.piq.fcarcade.home;

/** Presentation decisions only; never grants server authority or changes a stored mode. */
public final class HomeSyncMenuPolicy {
    /** A mask alone cannot opt an older addon into a new JNI room implementation. */
    public static int externalModes(int declared,int policyMask,boolean localAllowed,boolean jniDeclared) {
        return declared & ((policyMask & (localAllowed ? 7 : 5)) | (localAllowed ? (jniDeclared ? 24 : 8) : 0)) & 31;
    }
    private HomeSyncMenuPolicy() {}
    // Existing addon hooks may already filter policy from their supported mask.
    // RetroArch uses the local-sync lane in that contract; do not infer "unimplemented".
    static String addonUnavailable(int mode, java.util.function.Function<cn.piq.fcarcade.cabinet.CabinetSyncMode,String> reason) {
        String detail=reason.apply(mode==3?cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC
                :cn.piq.fcarcade.cabinet.CabinetSyncMode.checked(mode));
        if(detail==null||detail.isBlank())detail="附属未提供具体原因，请刷新或查看运行环境。";
        return mode==3?"Netplay 不可用："+detail:detail;
    }
    public static boolean supported(int mask, int mode) {
        return mode >= 0 && mode < 5 && (mask & (1 << mode)) != 0;
    }
    public static String selectedStatus(String operation, int selected, int mask, String unavailable) {
        return supported(mask, selected) ? operation
                : operation + " 当前所选模式不可用：" + unavailable;
    }
    public static boolean showMode(int mode, boolean external, boolean diagnosticsOnly, int mask) {
        return mode >= 0 && mode < 5 && (mode != 4 || !external || diagnosticsOnly || supported(mask, mode));
    }
    public static String buttonState(int mode, int selected, int mask, boolean editable, String unavailable) {
        if (!supported(mask, mode)) return unavailable;
        if (mode == selected) return "当前已选择此模式，无需重复选择。";
        return editable ? "管理员关机后可修改；已借手柄无需归还。"
                : "目前只读：需要管理员权限，并先关机、关闭加入或存档选择窗口。";
    }
}
