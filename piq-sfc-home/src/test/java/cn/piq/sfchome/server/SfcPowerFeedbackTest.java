package cn.piq.sfchome.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcPowerFeedbackTest {
    @Test void actualReadyAndDeferredPowerOffEachSoundOnce(){
        var feedback=new SfcPowerFeedback();
        assertTrue(feedback.started());assertFalse(feedback.started());
        feedback.deferStop();feedback.deferStop();
        assertTrue(feedback.finished());assertFalse(feedback.finished());
        assertFalse(feedback.started());feedback.deferStop();assertFalse(feedback.finished());
    }
    @Test void cancelledOrFailedPreparationNeverGetsPowerSounds(){
        var feedback=new SfcPowerFeedback();feedback.deferStop();
        assertFalse(feedback.finished());assertFalse(feedback.started());
    }
    @Test void immediateShutdownLeavesTheOffSoundToTheCommonAppliance(){
        var feedback=new SfcPowerFeedback();assertTrue(feedback.started());
        assertFalse(feedback.finished());feedback.deferStop();assertFalse(feedback.finished());
    }
    @Test void automaticStopDoesNotInventPhysicalPowerClick(){
        var feedback=new SfcPowerFeedback();assertTrue(feedback.started());
        assertFalse(feedback.finished());
    }
}
