package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerInputCaptureTest {
    @Test void newCaptureAllowsImmediateTap() {
        var c = new ControllerInputCapture(); assertEquals(1, c.sample(1)); assertEquals(0, c.sample(0));
    }
    @Test void suspendMustSendReleaseEvenWhenLastSampleWasZero() {
        var c = new ControllerInputCapture(); c.sample(1); c.sample(0);
        assertTrue(c.suspend()); assertFalse(c.suspend());
    }
    @Test void resumeDoesNotReplayHeldOrRepeatedOldInput() {
        var c = new ControllerInputCapture(); c.sample(1); c.suspend();
        assertEquals(0, c.sample(1)); assertEquals(0, c.sample(1)); assertEquals(0, c.sample(3));
        assertEquals(0, c.sample(0)); assertEquals(3, c.sample(3));
    }
    @Test void alreadySuspendedQueueIsNotRearmedByAnotherLifecycleClear() {
        var c = new ControllerInputCapture(); c.suspend(); c.suspend();
        assertEquals(0, c.sample(128)); assertEquals(0, c.sample(0)); assertEquals(128, c.sample(128));
    }
    @Test void resetAndMuteMustAlsoBeReleasedBeforeRearmingAfterGui() {
        var c = new ControllerInputCapture(); c.sample(256); c.suspend();
        assertEquals(0, c.sample(256)); assertEquals(0, c.sample(512));
        c.sample(0); assertEquals(256, c.sample(256));
    }
}
