package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PgmStartSequenceTest {
    @Test void waitsForChatToCloseThenHoldsAndReleasesOnce() {
        var s=new PgmStartSequence();assertTrue(s.arm(0));assertEquals(-1,s.poll(false,1));
        assertEquals(3080,s.poll(true,100));assertEquals(3080,s.poll(true,100+PgmStartSequence.HOLD-1));
        assertEquals(0,s.poll(true,100+PgmStartSequence.HOLD));assertEquals(-1,s.poll(true,100+PgmStartSequence.HOLD+1));
    }
    @Test void doesNotExtendOnDuplicateRequest() {
        var s=new PgmStartSequence();assertTrue(s.arm(0));assertEquals(3080,s.poll(true,1));assertFalse(s.arm(100));
        assertEquals(0,s.poll(true,1+PgmStartSequence.HOLD));
    }
    @Test void losesFocusAndNeverAutomaticallyRestarts() {
        var s=new PgmStartSequence();s.arm(0);assertEquals(3080,s.poll(true,1));assertEquals(-1,s.poll(false,2));assertEquals(-1,s.poll(true,3));
    }
    @Test void expiresWhileWaitingForInputAndCancelsOnExit() {
        var s=new PgmStartSequence();s.arm(0);assertEquals(-1,s.poll(true,PgmStartSequence.ARM_TIMEOUT));
        assertTrue(s.arm(200));s.cancel();assertEquals(-1,s.poll(true,201));
    }
    @Test void signedNanoTimeWrapIsSafe() {
        var s=new PgmStartSequence();long now=Long.MAX_VALUE-2;s.arm(now);assertEquals(3080,s.poll(true,now+1));
        assertEquals(0,s.poll(true,now+1+PgmStartSequence.HOLD));
    }
}
