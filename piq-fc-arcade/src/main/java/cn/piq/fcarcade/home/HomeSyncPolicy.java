// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetSyncMode;
import cn.piq.fcarcade.cabinet.LegacyLocalSyncPolicy;

/** Persisted names avoid ordinal migration; unimplemented lanes remain fail-closed. */
public final class HomeSyncPolicy {
    private HomeSyncPolicy() {}
    public static CabinetSyncMode persisted(String name) {
        if (name != null) for (var mode : CabinetSyncMode.values()) if (mode.name().equals(name)) return mode;
        return CabinetSyncMode.LOCAL_SYNC;
    }
    public static boolean runnable(CabinetSyncMode mode) { return mode != null; }
    /** Public home launches retire only the old local-sync route, not Netplay's shared enum. */
    public static boolean publicRunnable(CabinetSyncMode mode, boolean netplay) {
        return LegacyLocalSyncPolicy.mayStart(mode,netplay,false);
    }
    public static String legacyUnavailable() {
        return LegacyLocalSyncPolicy.RETIRED + " 请管理员关机后用螺丝刀修改；原配置保留。";
    }
    public static int supportedMask() { return LegacyLocalSyncPolicy.selectableMask(7); }
    /** New placements only; loaded NBT and explicit retired global preferences never migrate. */
    public static CabinetSyncMode placementMode(int preferred, int supported, CabinetSyncMode fallback) {
        int selected=cn.piq.fcarcade.config.GameConsoleAdminPolicy.selectNewMode(preferred,supported,fallback.ordinal());
        if(LegacyLocalSyncPolicy.retiredSelection(preferred))return CabinetSyncMode.LOCAL_SYNC;
        if(preferred==-1&&LegacyLocalSyncPolicy.retiredSelection(selected)){
            if((supported&1)!=0)return CabinetSyncMode.MEDIA;
            if((supported&4)!=0)return CabinetSyncMode.SERVER_MEDIA;
        }
        return CabinetSyncMode.checked(selected);
    }
    public static String unavailable(CabinetSyncMode mode) {
        if(mode==CabinetSyncMode.LOCAL_SYNC)return "服主已禁用本地输入同步；默认偏好保留，但本次不能开机。";
        return mode == CabinetSyncMode.MEDIA
                ? "服主已禁用玩家音画串流，请检查 piq-sync-server.toml 的 allowPlayerHosting。"
                : "服务端托管未启用或运行库不可用；请检查 piq-sync-server.toml 的 serverHosting 与服务器核心环境。";
    }
    public static boolean mayApply(int requested, int supported, boolean administrator, boolean busy) {
        return requested >= 0 && requested <= 2 && requested != 1 && (supported & (1 << requested)) != 0 && administrator && !busy;
    }
}
