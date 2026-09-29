package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetSyncMode;

/** Persisted names avoid ordinal migration; unimplemented lanes remain fail-closed. */
public final class HomeSyncPolicy {
    private HomeSyncPolicy() {}
    public static CabinetSyncMode persisted(String name) {
        if (name != null) for (var mode : CabinetSyncMode.values()) if (mode.name().equals(name)) return mode;
        return CabinetSyncMode.LOCAL_SYNC;
    }
    public static boolean runnable(CabinetSyncMode mode) { return mode != null; }
    public static int supportedMask() { return 7; }
    public static String unavailable(CabinetSyncMode mode) {
        if(mode==CabinetSyncMode.LOCAL_SYNC)return "服主已禁用本地输入同步；默认偏好保留，但本次不能开机。";
        return mode == CabinetSyncMode.MEDIA
                ? "服主已禁用玩家音画串流，请检查 piq-sync-server.toml 的 allowPlayerHosting。"
                : "服务端托管未启用或运行库不可用；请检查 piq-sync-server.toml 的 serverHosting 与服务器核心环境。";
    }
    public static boolean mayApply(int requested, int supported, boolean administrator, boolean busy) {
        return requested >= 0 && requested <= 2 && (supported & (1 << requested)) != 0 && administrator && !busy;
    }
}
