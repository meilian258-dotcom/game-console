package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetSyncMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSyncPolicyTest {
    @Test void legacyMissingAndUnknownNamesKeepLocalDefault() {
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.persisted(null));
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.persisted(""));
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.persisted("UNKNOWN"));
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.persisted("LOCAL_SYNC"));
    }
    @Test void playerMediaPreferenceNowHasARealExecutionLane() {
        assertEquals(CabinetSyncMode.MEDIA,HomeSyncPolicy.persisted("MEDIA"));
        assertTrue(HomeSyncPolicy.runnable(HomeSyncPolicy.persisted("MEDIA")));
        assertFalse(HomeSyncPolicy.unavailable(CabinetSyncMode.MEDIA).isBlank());
    }
    @Test void selectionRequiresModeSupportAdminAndIdle() {
        for(int mode=-1;mode<=3;mode++)for(boolean admin:new boolean[]{false,true})for(boolean busy:new boolean[]{false,true})
            assertEquals((mode>=0&&mode<=2)&&admin&&!busy,HomeSyncPolicy.mayApply(mode,HomeSyncPolicy.supportedMask(),admin,busy));
    }
    @Test void localAndImplementedServerLanesAreCapabilitiesNotServerOptIn() {
        assertEquals(7,HomeSyncPolicy.supportedMask());
        assertTrue(HomeSyncPolicy.runnable(CabinetSyncMode.LOCAL_SYNC));
        assertTrue(HomeSyncPolicy.runnable(CabinetSyncMode.SERVER_MEDIA));
        assertFalse(HomeSyncPolicy.runnable(null));
    }
}
