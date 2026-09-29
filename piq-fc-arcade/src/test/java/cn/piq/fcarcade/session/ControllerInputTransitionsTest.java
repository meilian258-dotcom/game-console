package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerInputTransitionsTest {
    @Test void tapBetweenFramesSurvivesAndReleases() {
        var q = new ControllerInputTransitions(); q.offer(1); q.offer(0);
        assertEquals(1, q.nextFrame()); assertEquals(0, q.nextFrame()); assertEquals(0, q.nextFrame());
    }
    @Test void twoTapsKeepAnUnpressedFrameBetweenThem() {
        var q = new ControllerInputTransitions();
        for (int mask : new int[]{1, 0, 1, 0}) q.offer(mask);
        for (int mask : new int[]{1, 0, 1, 0}) assertEquals(mask, q.nextFrame());
    }
    @Test void comboEdgesRemainOrderedAndRepeatDoesNotAutofire() {
        var q = new ControllerInputTransitions();
        for (int mask : new int[]{1, 1, 3, 3, 2, 0}) q.offer(mask);
        assertEquals(4, q.pendingCount());
        for (int mask : new int[]{1, 3, 2, 0}) assertEquals(mask, q.nextFrame());
    }
    @Test void heldInputPersistsWithoutNeedingRepeatEvents() {
        var q = new ControllerInputTransitions(); q.offer(128);
        for (int n = 0; n < 100; n++) assertEquals(128, q.nextFrame());
    }
    @Test void lifecycleClearDropsAllPendingPresses() {
        var q = new ControllerInputTransitions(); q.offer(1); q.offer(0); q.offer(2); q.clear();
        assertEquals(0, q.nextFrame()); assertEquals(0, q.pendingCount());
        q.offer(4); assertEquals(4, q.nextFrame());
    }
    @Test void overloadFailsNeutralAndDoesNotReplayUntilRelease() {
        var q = new ControllerInputTransitions(2); q.offer(1); q.offer(0);
        assertFalse(q.offer(1)); assertEquals(0, q.nextFrame()); assertEquals(0, q.pendingCount());
        assertFalse(q.offer(2)); assertEquals(0, q.nextFrame());
        assertTrue(q.offer(0)); assertTrue(q.offer(2)); assertEquals(2, q.nextFrame());
    }
    @Test void invalidMasksCannotLatchAnything() {
        var q = new ControllerInputTransitions(); q.offer(1);
        assertFalse(q.offer(256)); assertEquals(0, q.nextFrame());
        assertFalse(q.offer(-1)); assertEquals(0, q.nextFrame());
    }
}
