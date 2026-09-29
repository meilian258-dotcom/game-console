package cn.piq.sfchome.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcEditorPermissionsTest {
    @Test void zeroCapabilitiesFailClosed(){assertFalse(SfcEditorPermissions.browse(0));assertFalse(SfcEditorPermissions.upload(0,false));assertFalse(SfcEditorPermissions.upload(0,true));assertFalse(SfcEditorPermissions.admin(0));assertFalse(SfcEditorPermissions.selection(0,false,false));}
    @Test void browserNeedsExplicitServerRomUse(){assertTrue(SfcEditorPermissions.browse(1));assertFalse(SfcEditorPermissions.selection(1,false,false));assertTrue(SfcEditorPermissions.selection(17,false,false));assertFalse(SfcEditorPermissions.selection(17,true,false));assertFalse(SfcEditorPermissions.selection(17,true,true));assertFalse(SfcEditorPermissions.admin(17));}
    @Test void romAndCoverUploadAreIndependent(){assertTrue(SfcEditorPermissions.upload(3,false));assertFalse(SfcEditorPermissions.upload(3,true));assertFalse(SfcEditorPermissions.upload(5,false));assertTrue(SfcEditorPermissions.upload(5,true));}
    @Test void orphanUploadAndAdminBitsNeverBypassBrowsing(){for(int c:new int[]{2,4,6,8,14}){assertFalse(SfcEditorPermissions.browse(c));assertFalse(SfcEditorPermissions.upload(c,false));assertFalse(SfcEditorPermissions.upload(c,true));assertFalse(SfcEditorPermissions.admin(c));}}
    @Test void adminCapabilitiesEnableAllSupportedActions(){assertTrue(SfcEditorPermissions.admin(63));assertTrue(SfcEditorPermissions.selection(63,false,false));assertTrue(SfcEditorPermissions.selection(63,true,false));assertTrue(SfcEditorPermissions.selection(63,true,true));assertTrue(SfcEditorPermissions.selection(63,false,true));}
    @Test void unknownBitsAndNegativeMasksAreRejected(){for(int c:new int[]{-1,64,127,255,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->SfcEditorPermissions.checked(c));}
    @Test void everyWireMaskKeepsExactKnownBits(){for(int c=0;c<64;c++)assertEquals(c,SfcEditorPermissions.checked(c));}
    @Test void permissionRemovalChangesDecisionWithoutReopening(){assertTrue(SfcEditorPermissions.selection(7,true,false));assertFalse(SfcEditorPermissions.selection(5,true,false));assertTrue(SfcEditorPermissions.selection(5,true,true));assertTrue(SfcEditorPermissions.selection(17,false,false));assertFalse(SfcEditorPermissions.selection(1,false,false));assertTrue(SfcEditorPermissions.selection(33,false,true));assertFalse(SfcEditorPermissions.selection(17,false,true));}
}
