// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.config.GameConsoleAdminPolicy;

/** Placement-only policy: old hardware, explicit choices and non-JNI variants never opt in. */
final class CabinetPlacementDefaults {
    private CabinetPlacementDefaults() {}

    static boolean jni(boolean newPlacement, int preferred, boolean localAllowed,
                       boolean supported, boolean jniVariant) {
        if (!GameConsoleAdminPolicy.validMode(preferred))
            throw new IllegalArgumentException("Invalid cabinet placement preference");
        return newPlacement && preferred == -1 && localAllowed && supported && jniVariant;
    }

    /**
     * Automatic non-Netplay defaults never choose the retired lane. A stored explicit
     * preference for it stays retired and requires reselection, not silent migration.
     * No-capability MEDIA stays blocked at launch.
     */
    static CabinetSyncMode mediaMode(int preferred, boolean playerAllowed, boolean hosted) {
        if (LegacyLocalSyncPolicy.retiredSelection(preferred)) return CabinetSyncMode.LOCAL_SYNC;
        int supported=(playerAllowed?1:0)|(hosted?4:0);
        return CabinetSyncMode.checked(GameConsoleAdminPolicy.selectNewMode(preferred,supported,hosted?2:0));
    }
}
