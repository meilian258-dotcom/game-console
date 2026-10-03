package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSyncMenuPolicyTest {
    @Test void filteredAddonNetplayUsesExistingLocalHookAndKeepsRealPolicyReason(){
        var called=new java.util.ArrayList<cn.piq.fcarcade.cabinet.CabinetSyncMode>();
        String actual=HomeSyncMenuPolicy.addonUnavailable(3,mode->{called.add(mode);return HomeSyncPolicy.unavailable(mode);});
        assertEquals(java.util.List.of(cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC),called);
        assertEquals("Netplay 不可用："+HomeSyncPolicy.unavailable(cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC),actual);
        assertFalse(actual.contains("尚未提供该 Netplay"));
    }
    @Test void addonSpecificReasonsArePreservedWithoutInventingServerRestrictions(){
        for(int mode=0;mode<3;mode++){
            final int expected=mode;
            assertEquals("未实现",HomeSyncMenuPolicy.addonUnavailable(mode,lane->{assertEquals(expected,lane.ordinal());return "未实现";}));
        }
        assertEquals("Netplay 不可用：未验证",HomeSyncMenuPolicy.addonUnavailable(3,lane->"未验证"));
        assertFalse(HomeSyncMenuPolicy.addonUnavailable(3,lane->null).contains("null"));
    }
    @Test void mediaOnlyDeviceDoesNotInheritUnrelatedLocalFailure(){
        assertEquals("已读取",HomeSyncMenuPolicy.selectedStatus("已读取",0,1,"本地同步未实现"));
        assertEquals("当前已选择此模式，无需重复选择。",HomeSyncMenuPolicy.buttonState(0,0,1,true,""));
    }
    @Test void selectedUnsupportedModeKeepsStoredSelectionButExplainsFailure(){
        assertEquals("已读取 当前所选模式不可用：附属未实现",HomeSyncMenuPolicy.selectedStatus("已读取",1,1,"附属未实现"));
        assertEquals("附属未实现",HomeSyncMenuPolicy.buttonState(1,1,1,true,"附属未实现"));
    }
    @Test void labelsDistinguishPermissionAndCurrentSelection(){
        assertTrue(HomeSyncMenuPolicy.buttonState(0,1,7,false,"").contains("只读"));
        assertTrue(HomeSyncMenuPolicy.buttonState(0,1,7,true,"").contains("管理员"));
        assertTrue(HomeSyncMenuPolicy.buttonState(0,0,7,false,"").contains("当前"));
    }
    @Test void externalDeviceHasNoFcJniRowUnlessExplicitlyCapable(){
        assertFalse(HomeSyncMenuPolicy.showMode(4,true,false,1));
        assertFalse(HomeSyncMenuPolicy.showMode(4,true,false,15));
        assertTrue(HomeSyncMenuPolicy.showMode(4,true,false,17));
        assertTrue(HomeSyncMenuPolicy.showMode(4,false,false,31));
        assertTrue(HomeSyncMenuPolicy.showMode(4,true,true,0));
        for(int mode=0;mode<4;mode++)assertTrue(HomeSyncMenuPolicy.showMode(mode,true,false,1));
    }
    @Test void masksNeverGrantInvalidModes(){
        for(int mask=0;mask<32;mask++)for(int mode=-1;mode<=5;mode++)
            assertEquals(mode>=0&&mode<5&&(mask&(1<<mode))!=0,HomeSyncMenuPolicy.supported(mask,mode));
    }
    @Test void externalJniRequiresExplicitDeclarationAndServerPolicy(){
        for(int declared=0;declared<64;declared++)for(boolean local:new boolean[]{false,true})for(boolean jni:new boolean[]{false,true}){
            int policy=local?7:5;int actual=HomeSyncMenuPolicy.externalModes(declared,policy,local,jni);
            assertEquals(declared&(policy|(local?(jni?24:8):0))&31,actual);
            assertEquals(local&&jni&&(declared&16)!=0,(actual&16)!=0);
            assertEquals(local&&(declared&8)!=0,(actual&8)!=0);
        }
        assertEquals(15,HomeSyncMenuPolicy.externalModes(15,7,true,false)); // Existing SFC.
        assertEquals(1,HomeSyncMenuPolicy.externalModes(1,7,true,true)); // An opt-in is not a mode claim.
    }
}
