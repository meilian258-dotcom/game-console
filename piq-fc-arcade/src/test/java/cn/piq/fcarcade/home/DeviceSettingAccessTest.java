package cn.piq.fcarcade.home;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeviceSettingAccessTest {
    @Test void exactReasonsDoNotSuggestEnablingUnimplementedModes(){
        assertEquals(DeviceSettingAccess.READ_FAILED,DeviceSettingAccess.resolve(false,false,true,false));
        assertEquals(DeviceSettingAccess.DIAGNOSTICS_ONLY,DeviceSettingAccess.resolve(true,true,true,false));
        assertEquals(DeviceSettingAccess.ADMIN_REQUIRED,DeviceSettingAccess.resolve(true,false,false,true));
        assertEquals(DeviceSettingAccess.BUSY,DeviceSettingAccess.resolve(true,false,true,true));
        assertTrue(DeviceSettingAccess.resolve(true,false,true,false).editable());
        assertEquals("未实现",HomeSyncMenuPolicy.buttonState(1,0,1,false,"未实现"));
        assertEquals(DeviceSettingAccess.BUSY.reason(),HomeSyncMenuPolicy.buttonState(1,0,3,false,DeviceSettingAccess.BUSY.reason()));
    }
}
