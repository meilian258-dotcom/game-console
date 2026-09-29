package cn.piq.fcarcade.client.zapper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZapperInputStateTest {
    @Test void preheldMouseCannotFireOnJoin() {
        var s=new ZapperInputState();assertFalse(s.sample(true,true));assertFalse(s.armed());
        assertFalse(s.sample(true,false));assertTrue(s.armed());assertTrue(s.sample(true,true));
    }
    @Test void shortPressAndReleaseRemainDistinctSamples() {
        var s=new ZapperInputState();s.sample(true,false);
        for(int i=0;i<100;i++){assertTrue(s.sample(true,true));assertFalse(s.sample(true,false));}
    }
    @Test void focusAndOwnershipLossImmediatelyNeutralizeAndRequireRelease() {
        var s=new ZapperInputState();s.sample(true,false);assertTrue(s.sample(true,true));
        assertFalse(s.sample(false,true));assertFalse(s.trigger());assertFalse(s.armed());
        assertFalse(s.sample(true,true));assertFalse(s.sample(true,false));assertTrue(s.sample(true,true));
    }
    @Test void epochResetUsesSameNeutralBoundaryAsFocusLoss() {
        var s=new ZapperInputState();s.sample(true,false);s.sample(true,true);s.clear();
        assertFalse(s.sample(true,true));assertFalse(s.trigger());assertFalse(s.sample(true,false));assertTrue(s.sample(true,true));
    }
    @Test void visualQueryNeverArmsPreheldInputOrBypassesAuthorization() {
        var s=new ZapperInputState();
        assertFalse(s.visualTrigger(true));assertFalse(s.armed());
        s.sample(true,true);assertFalse(s.visualTrigger(true));assertFalse(s.armed());
        s.sample(true,false);s.sample(true,true);
        assertTrue(s.visualTrigger(true));assertFalse(s.visualTrigger(false));
        assertTrue(s.trigger());assertTrue(s.armed()); // Presentation is read-only.
    }
    @Test void visualQueryDropsOnReleaseClearOrRevocationAndCannotRestoreOldEpoch() {
        var s=new ZapperInputState();s.sample(true,false);s.sample(true,true);
        assertTrue(s.visualTrigger(true));s.sample(true,false);assertFalse(s.visualTrigger(true));
        s.sample(true,true);s.clear();assertFalse(s.visualTrigger(true));
        s.sample(true,true);assertFalse(s.visualTrigger(true));
        s.sample(true,false);s.sample(true,true);s.sample(false,true);assertFalse(s.visualTrigger(true));
    }
}
