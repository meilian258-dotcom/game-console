package cn.piq.computer.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvzBackendPreferenceTest {
    @Test void absentChoiceDefaultsJniButExplicitLegacyChoicesRemain() {
        assertTrue(PvzBackendPreference.jni(null,true,true));
        assertTrue(PvzBackendPreference.jni("jni-v1",true,true));
        for(String choice:new String[]{"process","jni","", "unknown"})
            assertFalse(PvzBackendPreference.jni(choice,true,true));
    }
    @Test void unsupportedPlatformOrUnreadableConfigCannotEnableNative() {
        assertFalse(PvzBackendPreference.jni(null,false,true));
        assertFalse(PvzBackendPreference.jni("jni-v1",false,true));
        assertFalse(PvzBackendPreference.jni(null,true,false));
        assertFalse(PvzBackendPreference.jni("jni-v1",true,false));
    }
}
