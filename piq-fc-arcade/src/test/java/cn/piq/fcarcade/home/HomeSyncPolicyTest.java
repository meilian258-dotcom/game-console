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
            assertEquals((mode==0||mode==2)&&admin&&!busy,HomeSyncPolicy.mayApply(mode,HomeSyncPolicy.supportedMask(),admin,busy));
    }
    @Test void localAndImplementedServerLanesAreCapabilitiesNotServerOptIn() {
        assertEquals(5,HomeSyncPolicy.supportedMask());
        assertTrue(HomeSyncPolicy.runnable(CabinetSyncMode.LOCAL_SYNC));
        assertTrue(HomeSyncPolicy.runnable(CabinetSyncMode.SERVER_MEDIA));
        assertFalse(HomeSyncPolicy.runnable(null));
    }
    @Test void publicHomesRetireOnlyOldLocalWhileTheSharedInternalEnumRemainsReadable() {
        for(var mode:CabinetSyncMode.values())for(boolean netplay:new boolean[]{false,true})
            assertEquals(mode!=CabinetSyncMode.LOCAL_SYNC||netplay,HomeSyncPolicy.publicRunnable(mode,netplay));
        assertFalse(HomeSyncPolicy.publicRunnable(null,false));
        assertFalse(HomeSyncPolicy.publicRunnable(null,true));
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.persisted("LOCAL_SYNC"));
        assertTrue(HomeSyncPolicy.runnable(CabinetSyncMode.LOCAL_SYNC),"Do not break shared cabinet/private ABI");
        assertFalse(HomeSyncPolicy.mayApply(1,31,true,false),"Even a stale mask must not re-enable legacy home sync");
        assertTrue(HomeSyncPolicy.legacyUnavailable().contains("原配置保留"));
        assertTrue(HomeSyncPolicy.legacyUnavailable().contains("旧核心和存档保留，不自动切换或转换"));
    }
    @Test void onlyNewAutomaticPlacementCanChooseAnAvailableNonRetiredFallback() {
        assertEquals(CabinetSyncMode.MEDIA,HomeSyncPolicy.placementMode(-1,5,CabinetSyncMode.LOCAL_SYNC));
        assertEquals(CabinetSyncMode.MEDIA,HomeSyncPolicy.placementMode(-1,1,CabinetSyncMode.LOCAL_SYNC));
        assertEquals(CabinetSyncMode.SERVER_MEDIA,HomeSyncPolicy.placementMode(-1,4,CabinetSyncMode.LOCAL_SYNC));
        assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.placementMode(-1,0,CabinetSyncMode.LOCAL_SYNC));
        for(int mask=0;mask<32;mask++)for(var fallback:CabinetSyncMode.values())
            assertEquals(CabinetSyncMode.LOCAL_SYNC,HomeSyncPolicy.placementMode(1,mask,fallback),
                    "An explicit retired global preference remains visibly disabled, not silently reinterpreted");
        assertEquals(CabinetSyncMode.SERVER_MEDIA,HomeSyncPolicy.placementMode(2,5,CabinetSyncMode.LOCAL_SYNC));
        assertEquals(CabinetSyncMode.MEDIA,HomeSyncPolicy.placementMode(0,5,CabinetSyncMode.LOCAL_SYNC));
        assertThrows(IllegalArgumentException.class,()->HomeSyncPolicy.placementMode(3,31,CabinetSyncMode.LOCAL_SYNC));
        assertThrows(IllegalArgumentException.class,()->HomeSyncPolicy.placementMode(-2,31,CabinetSyncMode.LOCAL_SYNC));
    }
}
