package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TvRemoteSettingsPolicyTest {
    @Test void unknownActionsAndInvalidBooleanValuesAreRejected() {
        assertFalse(TvRemoteSettingsPolicy.valid(-1, 0));
        assertFalse(TvRemoteSettingsPolicy.valid(8, 0));
        for (int action : new int[]{0, 1, 3, 4, 5, 6, 7}) {
            assertFalse(TvRemoteSettingsPolicy.valid(action, -1));
            assertFalse(TvRemoteSettingsPolicy.valid(action, 2));
            assertTrue(TvRemoteSettingsPolicy.valid(action, 0));
            assertTrue(TvRemoteSettingsPolicy.valid(action, 1));
        }
    }
    @Test void volumeIsBoundedAndDoesNotUseBooleanRules() {
        assertFalse(TvRemoteSettingsPolicy.valid(2, -1));
        for (int volume = 0; volume <= 100; volume++) assertTrue(TvRemoteSettingsPolicy.valid(2, volume));
        assertFalse(TvRemoteSettingsPolicy.valid(2, 101));
    }
    @Test void ordinaryTvPreferencesDoNotNeedAdminOrConsole() {
        for (int action = 0; action <= 5; action++)
            assertTrue(TvRemoteSettingsPolicy.mayApply(action, 0, false, false));
    }
    @Test void legacyConsoleOptionsRemainDecodableButNeverWritableThroughRemote() {
        for (int action : new int[]{TvRemoteSettingsPolicy.OCCUPANCY, TvRemoteSettingsPolicy.APPROVAL}) {
            for (int value = 0; value <= 1; value++) {
                assertTrue(TvRemoteSettingsPolicy.valid(action, value));
                for (boolean console : new boolean[]{false, true})
                    for (boolean administrator : new boolean[]{false, true})
                        assertFalse(TvRemoteSettingsPolicy.mayApply(action, value, console, administrator));
            }
        }
        assertFalse(TvRemoteSettingsPolicy.mayApply(7, 5, true, true));
    }
}
