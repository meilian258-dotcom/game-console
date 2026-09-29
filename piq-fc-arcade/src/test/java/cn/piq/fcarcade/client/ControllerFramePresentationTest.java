package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerFramePresentationTest {
    @Test void shortActualCorePulseIsVisibleForOneRender() {
        var p = new ControllerFramePresentation(); long r = p.revision();
        p.complete(r, 1, 0); p.complete(r, 0, 0);
        assertEquals(0, p.latest(0)); p.present(); assertEquals(1, p.presented(0));
        p.present(); assertEquals(0, p.presented(0));
    }
    @Test void portsAndHeldStatesStaySeparate() {
        var p = new ControllerFramePresentation(); p.complete(p.revision(), 1, 128);
        p.present(); assertEquals(1, p.presented(0)); assertEquals(128, p.presented(1));
        p.present(); assertEquals(128, p.presented(1));
    }
    @Test void lifecycleClearRejectsCompletionOfOldInFlightFrame() {
        var p = new ControllerFramePresentation(); long stale = p.revision();
        p.complete(stale, 1, 1); p.clear(); p.complete(stale, 255, 255); p.present();
        assertEquals(0, p.latest(0)); assertEquals(0, p.presented(1));
        p.complete(p.revision(), 2, 0); p.present(); assertEquals(2, p.presented(0));
    }
    @Test void noInputSubmissionCanCreatePresentationWithoutACompletedFrame() {
        var p = new ControllerFramePresentation(); p.present(); assertEquals(0, p.presented(0));
        assertEquals(0, p.presented(-1)); assertEquals(0, p.presented(2));
    }
}
