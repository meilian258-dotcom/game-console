// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.home;

/** Presentation decisions only; never grants server authority or changes a stored mode. */
public final class HomeSyncMenuPolicy {
    /** A mask alone cannot opt an older addon into a new JNI room implementation. */
    public static int externalModes(int declared,int policyMask,boolean localAllowed,boolean jniDeclared) {
        return declared & ((policyMask & 5) | (localAllowed ? (jniDeclared ? 24 : 8) : 0)) & 29;
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
        return mode >= 0 && mode < 6 && mode != 1 && (mask & (1 << mode)) != 0;
    }
    public static String selectedStatus(String operation, int selected, int mask, String unavailable) {
        return supported(mask, selected) ? operation
                : operation + " 当前所选模式不可用：" + unavailable;
    }
    public static boolean showMode(int mode, boolean external, boolean diagnosticsOnly, int mask) {
        // The screen retains a selected legacy row only to explain refusal, never as a new option.
        return mode >= 0 && mode < 6 && mode != 1 && (mode != 5 || !external && !diagnosticsOnly);
    }
    public static java.util.List<Integer> visibleModes(int selected,boolean external,boolean diagnosticsOnly,int mask) {
        var modes=new java.util.ArrayList<Integer>(6);
        for(int mode=0;mode<6;mode++)if(showMode(mode,external,diagnosticsOnly,mask)||mode==1&&selected==1)modes.add(mode);
        return java.util.List.copyOf(modes);
    }
    /** Unknown variants remain visibly distinct from the historical empty/Mesen selection. */
    static int fcMode(boolean netplay,boolean jni,String variant,int oldMode) {
        return !netplay?oldMode:jni?4:variant.isEmpty()?3:5;
    }
    public static String buttonState(int mode, int selected, int mask, boolean editable, String unavailable) {
        if (!supported(mask, mode)) return unavailable;
        if (mode == selected) return "当前已选择此模式，无需重复选择。";
        if(!editable&&unavailable!=null&&!unavailable.isBlank())return unavailable;
        return editable ? "管理员关机后可修改；已借手柄无需归还。"
                : "目前只读：需要管理员权限，并先关机、关闭加入或存档选择窗口。";
    }
}
