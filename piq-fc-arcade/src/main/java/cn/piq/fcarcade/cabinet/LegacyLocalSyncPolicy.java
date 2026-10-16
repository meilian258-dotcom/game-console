// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.cabinet;

/** Retirement of the old selectable lane, not of Netplay's shared wire/persistence marker. */
public final class LegacyLocalSyncPolicy {
    public static final String RETIRED = "旧本地输入同步已停用；请在设备空闲时明确选择 Netplay 或可用的音画串流。旧核心和存档保留，不自动切换或转换。";

    private LegacyLocalSyncPolicy() {}

    /** Keep wire mode 1 readable, while old clients cannot choose it for a new setting. */
    public static boolean retiredSelection(int mode) { return mode == CabinetSyncMode.LOCAL_SYNC.ordinal(); }

    public static int selectableMask(int declared) { return declared & ~(1 << CabinetSyncMode.LOCAL_SYNC.ordinal()); }

    /**
     * New-room admission only; existing room teardown must not consult this gate.
     * The ordinary cabinet's FC/NES backend still has only its original internal route.
     * A missing mode never inherits either exception.
     */
    public static boolean mayStart(CabinetSyncMode mode, boolean netplay, boolean originalFcRoute) {
        return mode != null && (mode != CabinetSyncMode.LOCAL_SYNC || netplay || originalFcRoute);
    }
}
