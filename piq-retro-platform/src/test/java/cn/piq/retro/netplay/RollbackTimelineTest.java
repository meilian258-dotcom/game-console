// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.netplay;

import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTimelineTest {
    static class Core implements RollbackTimeline.Core<Long> {
        long state = 7; int presented, replayed;
        public byte[] save() { return ByteBuffer.allocate(8).putLong(state).array(); }
        public void restore(byte[] value) { state = ByteBuffer.wrap(value).getLong(); }
        public Long step(int a, int b, boolean present) {
            state = state * 31 + a * 65537L + b;
            if (present) presented++; else replayed++;
            return state;
        }
    }
    @Test void lateRemoteInputsMatchNoDelayReferenceWithoutRepublishing() {
        var actual = new Core(); var reference = new Core();
        var t = new RollbackTimeline<>(actual, 0);
        for (int i = 0; i < 12; i++) { t.advance(i, 0, 1); reference.step(i, i < 4 ? 3 : 9, true); }
        assertEquals(0, t.supply(0, 1, 3));
        assertEquals(4, t.supply(4, 1, 9));
        assertEquals(reference.state, actual.state);
        assertEquals(12, actual.presented); assertEquals(20, actual.replayed);
        assertEquals(20, t.replayedFrames());
        assertEquals(-1, t.supply(4, 1, 9));
        assertThrows(IllegalArgumentException.class, () -> t.supply(4, 1, 4));
    }
    @Test void realInputStopsPredictionPropagation() {
        var c = new Core(); var t = new RollbackTimeline<>(c, 40);
        t.advance(1, 0, 1); t.advance(2, 0, 1); t.advance(3, 8, 3); t.advance(4, 8, 1);
        t.supply(40, 1, 5);
        assertEquals(5, t.input(41).p2()); assertEquals(8, t.input(42).p2()); assertEquals(8, t.input(43).p2());
        assertEquals(3, t.input(40).known()); assertEquals(1, t.input(41).known());
    }
    @Test void canonicalBatchReplaysOnceAndInvalidRangeDoesNotMutate() {
        var c = new Core(); var t = new RollbackTimeline<>(c, 0);
        t.advance(1, 0, 1); t.advance(2, 0, 1); t.advance(3, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> t.canonical(List.of(
                new RollbackTimeline.Input(0, 4, 5, 3), new RollbackTimeline.Input(4, 4, 5, 3))));
        assertEquals(1, t.input(0).p1());
        assertTrue(t.canonical(List.of(new RollbackTimeline.Input(0, 4, 5, 3), new RollbackTimeline.Input(1, 6, 7, 3))));
        assertEquals(3, c.replayed); assertEquals(3, c.presented);
        var reference = new Core(); reference.step(4,5,true); reference.step(6,7,true); reference.step(3,0,true);
        assertEquals(reference.state, c.state);
    }
    @Test void historyIsBoundedAndDiscardedInputsCannotBeReintroduced() {
        var t = new RollbackTimeline<>(new Core(), 10);
        for (int i=0;i<RollbackTimeline.HISTORY;i++) t.advance(0,0,3);
        assertFalse(t.canAdvance()); assertThrows(IllegalStateException.class, () -> t.advance(0,0,3));
        t.discardBefore(26); assertEquals(16,t.retained()); assertTrue(t.canAdvance());
        assertThrows(IllegalArgumentException.class, () -> t.supply(25,1,0));
        assertThrows(IllegalArgumentException.class, () -> t.discardBefore(43));
    }
    @Test void snapshotsAreDetachedAndRoundTripExact() {
        var c = new Core(); var t = new RollbackTimeline<>(c,0);
        t.advance(1,2,3); t.advance(3,4,3);
        byte[] state = t.stateBefore(1); state[0]++;
        assertNotEquals(state[0],t.stateBefore(1)[0]);
        var replay = new Core(); replay.restore(t.stateBefore(1)); replay.step(3,4,true);
        assertEquals(c.state,replay.state);
        assertArrayEquals(c.save(),t.stateBefore(2));
    }
    @Test void ownerAndSizeAndMasksAreFailClosed() throws Exception {
        var t = new RollbackTimeline<>(new Core(),0);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { t.advance(0,0,3); } catch(Throwable e) { failure.set(e); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class,failure.get());
        assertThrows(IllegalArgumentException.class, () -> t.advance(-1,0,3));
        assertThrows(IllegalArgumentException.class, () -> new RollbackTimeline.Input(0,0,0,4));
        var bad = new RollbackTimeline<>(new Core(){@Override public byte[] save(){return new byte[RollbackTimeline.MAX_STATE+1];}},0);
        assertThrows(IllegalStateException.class, () -> bad.advance(0,0,3));
    }
    @Test void randomizedDelayedInputsMatchReferenceAcrossThousandsOfFrames() {
        var c = new Core(); var r = new Core(); var t = new RollbackTimeline<>(c,0);
        var random = new Random(76124); var inputs = new ArrayList<Integer>();
        int prediction=0;
        for(int f=0;f<4000;f++) {
            int remote=random.nextInt(65536); inputs.add(remote);
            t.advance(f&65535,prediction,1); r.step(f&65535,remote,true);
            if(f>=6) { t.supply(f-6,1,inputs.get(f-6)); prediction=inputs.get(f-6); }
            if(f>=12) t.discardBefore(f-11);
        }
        for(int f=3994;f<4000;f++) t.supply(f,1,inputs.get(f));
        assertEquals(r.state,c.state); assertEquals(4000,c.presented); assertTrue(c.replayed>20000);
    }
    @Test void auxiliaryInputsAreRecordedAndReplayedNotResampled() {
        class FullCore extends Core {
            @Override public Long step(RollbackTimeline.Input input,boolean present) {
                long base=super.step(input.p1(),input.p2(),present);
                return state=base+input.p3()*7L+input.p4()*11L+input.gun()*13L;
            }
        }
        var actual=new FullCore();var reference=new FullCore();var t=new RollbackTimeline<>(actual,0);
        var input0=new RollbackTimeline.Input(0,1,0,4,5,65536,1);
        var input1=new RollbackTimeline.Input(1,2,0,8,9,(1<<17)|230|(211<<8),1);
        t.advance(input0);t.advance(input1);t.supply(0,1,6);
        reference.step(new RollbackTimeline.Input(0,1,6,4,5,65536,3),true);
        reference.step(new RollbackTimeline.Input(1,2,6,8,9,input1.gun(),1),true);
        assertEquals(reference.state,actual.state);assertEquals(2,actual.replayed);
        assertEquals(input1.gun(),t.input(1).gun());assertEquals(8,t.input(1).p3());
        var corrected=new RollbackTimeline.Input(1,2,6,8,9,65536,3);
        reference.restore(t.stateBefore(1));reference.step(corrected,true);
        assertTrue(t.canonical(List.of(corrected)));assertEquals(reference.state,actual.state);
        assertThrows(IllegalArgumentException.class,()->new RollbackTimeline.Input(0,0,0,0,0,65537,3));
        assertThrows(IllegalArgumentException.class,()->new RollbackTimeline.Input(0,0,0,0,0,240<<8,3));
    }
}
