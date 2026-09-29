package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MouseControlRegressionTest {
    private static KeyboardControlState functional(){return new KeyboardControlState(KeyboardConfig.Preset.LEGACY,new int[][]{{-1000,75},{-1001}});}
    @Test void mouseFunctionIsCapturedEvenIfWorldUsesTheSameButton(){var s=functional();assertTrue(s.captures(-1000,false));assertTrue(s.captures(-1000,true));assertFalse(s.captures(-1002,false));assertFalse(s.captures(-1,false));}
    @Test void fastMouseEdgesReachGameWithoutPhysicalFallback(){var s=functional();s.activate(true,k->false);for(int i=0;i<40;i++){assertTrue(s.key(-1000,1));assertEquals(1,s.mask(0));assertTrue(s.key(-1000,0));assertEquals(0,s.mask(0));}}
    @Test void aliasesReleaseIndependently(){var s=functional();s.activate(true,k->false);s.key(-1000,1);s.key(75,1);s.key(-1000,0);assertEquals(1,s.mask(0));s.key(75,0);assertEquals(0,s.mask(0));}
    @Test void mouseHeldDuringPauseMustBeNeutralBeforeRearming(){var s=functional();s.activate(true,k->false);s.key(-1000,1);s.pause();s.activate(true,k->k==-1000);assertFalse(s.armed());assertEquals(0,s.mask(1));s.key(-1000,0);s.activate(true,k->false);assertTrue(s.armed());assertEquals(0,s.mask(0));}
    @Test void mouseMovementConflictRemainsWorldOnlyUntilPositionLocked(){int[][] keys={{-1},{-1},{-1},{-1},{-1000}};var s=new KeyboardControlState(KeyboardConfig.Preset.LEGACY,keys);assertFalse(s.captures(-1000,true));s.activate(true,k->true,k->k==-1000);assertTrue(s.armed());assertEquals(0,s.mask(16,k->k==-1000));s.toggle();assertTrue(s.captures(-1000,true));s.activate(true,k->true,k->k==-1000);assertFalse(s.armed());s.activate(true,k->false,k->k==-1000);s.key(-1000,1,true);assertEquals(16,s.mask(0,k->k==-1000));}
    @Test void noInputSurvivesReturnOrAuthorityLoss(){var s=functional();s.activate(true,k->false);s.key(-1001,1);assertEquals(2,s.mask(0));s.activate(false,k->false);assertEquals(0,s.mask(3));s.activate(true,k->false);assertEquals(0,s.mask(0));}
}
