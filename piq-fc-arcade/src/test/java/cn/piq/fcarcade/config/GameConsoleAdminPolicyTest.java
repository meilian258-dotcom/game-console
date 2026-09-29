package cn.piq.fcarcade.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameConsoleAdminPolicyTest {
    @Test void automaticNewFcChoosesJniOnlyWhenAdvertisedAndNotExplicitlyOverridden(){
        for(int mask=0;mask<=31;mask++)for(int preference=-1;preference<=2;preference++)
            assertEquals(preference==-1&&(mask&16)!=0,GameConsoleAdminPolicy.defaultFcJni(preference,mask));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.defaultFcJni(-2,31));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.defaultFcJni(-1,32));
    }
    @Test void selectedModesNeverExpandCapabilities() {
        for(int mask=0;mask<=31;mask++)for(int preferred=-1;preferred<=2;preferred++)for(int fallback=0;fallback<=2;fallback++) {
            int result=GameConsoleAdminPolicy.selectNewMode(preferred,mask,fallback);
            assertEquals(preferred>=0&&(mask&(1<<preferred))!=0?preferred:fallback,result);
        }
    }
    @Test void netplayCapabilityDoesNotInvalidateFcOrSfcPlacementDefaults() {
        // HomeSyncSettings combines the legacy lanes with the Netplay capability (bit 3).
        // The reported SFC onLoad crash occurs before a game/core is started.
        for(int mask:new int[]{8,10,11,14,15}) {
            assertEquals(1,GameConsoleAdminPolicy.selectNewMode(-1,mask,1));
            assertEquals((mask&1)!=0?0:1,GameConsoleAdminPolicy.selectNewMode(0,mask,1));
            assertEquals(1,GameConsoleAdminPolicy.selectNewMode(1,mask,1));
            assertEquals((mask&4)!=0?2:1,GameConsoleAdminPolicy.selectNewMode(2,mask,1));
        }
    }
    @Test void advertisingNetplayDoesNotSelectItAsAnAutomaticDefault() {
        for(int extra:new int[]{8,16,24})for(int legacy=0;legacy<=7;legacy++)for(int preferred=-1;preferred<=2;preferred++)for(int fallback=0;fallback<=2;fallback++) {
            assertEquals(GameConsoleAdminPolicy.selectNewMode(preferred,legacy,fallback),
                    GameConsoleAdminPolicy.selectNewMode(preferred,legacy|extra,fallback));
        }
        // Experimental Netplay is an explicit device choice, not a global placement default.
        assertFalse(GameConsoleAdminPolicy.validMode(3));
        assertFalse(GameConsoleAdminPolicy.validMode(4));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(4,31,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(1,31,4));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(3,15,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(1,15,3));
    }
    @Test void rangesHaveFiniteBoundsAndExitHysteresis() {
        assertFalse(GameConsoleAdminPolicy.validRange(3));assertFalse(GameConsoleAdminPolicy.validRange(65));
        for(int range=4;range<=64;range++)assertEquals(range+4,GameConsoleAdminPolicy.exitRange(range));
        assertEquals(16,GameConsoleAdminPolicy.persistedRange(0,16));
        assertEquals(0,GameConsoleAdminPolicy.persistedRange(0,0)); // Missing old tags remain absent.
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.exitRange(Integer.MAX_VALUE));
    }
    @Test void malformedDefaultsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(-2,7,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(3,7,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(1,32,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(1,-1,1));
        assertThrows(IllegalArgumentException.class,()->GameConsoleAdminPolicy.selectNewMode(1,7,3));
    }
}
