// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdVisualInputTest {
    @Test void normalReleaseAndHeartbeatAreNotLifecycleResets(){
        var s=new MdVisualInputState();s.offer(1);assertFalse(s.poll(0).reset());
        s.offer(0);var released=s.poll(2);assertEquals(0,released.mask());assertEquals(0,released.pressedMask());assertFalse(released.reset());
        assertFalse(s.poll(12).reset());assertTrue(s.cancel(13).reset());assertNull(s.cancel(13));
        s.offer(2);assertTrue(s.cancel(14).reset());
    }
    @Test void coalescesShortTapWithoutChangingFinalHeldMask(){
        var s=new MdVisualInputState();assertEquals(0,s.poll(0).mask());
        s.offer(1);s.offer(0);assertNull(s.poll(1));var tap=s.poll(2);
        assertEquals(0,tap.mask());assertEquals(1,tap.pressedMask());assertEquals(2,tap.sequence());
        assertNull(s.poll(3));assertNull(s.poll(11));assertEquals(0,s.poll(12).pressedMask());
    }
    @Test void independentPortsAndNewLoansHaveIndependentSequences(){
        var a=new MdVisualInputState();var b=new MdVisualInputState();a.offer(256);b.offer(2048);
        assertEquals(256,a.poll(5).mask());assertEquals(2048,b.poll(5).mask());
        assertEquals(2,a.clear(6).sequence());assertEquals(1,new MdVisualInputState().poll(6).sequence());
        assertEquals(2048,b.poll(15).mask());
    }
    @Test void revokeOrRejectedAuthorityClearsPendingPulsesImmediately(){
        var s=new MdVisualInputState();s.poll(0);s.offer(1);s.offer(0);
        var clear=s.cancel(1);assertEquals(0,clear.mask());assertEquals(0,clear.pressedMask());assertNull(s.cancel(1));
        assertNull(s.poll(2));assertEquals(0,s.poll(11).pressedMask());
    }
    @Test void acceptedInputGateIsRequiredBeforeOfferingVisual(){
        var c=new Object();var loan=java.util.UUID.randomUUID();var gate=new MdPublicInputGate(c,loan,8,1);var s=new MdVisualInputState();
        var result=gate.accept(new Object(),8,1,loan,1,4095,0);
        if(result==MdPublicInputGate.Result.ACCEPT)s.offer(4095);
        assertEquals(0,s.poll(0).mask());
        result=gate.accept(c,8,1,loan,1,256,1);if(result==MdPublicInputGate.Result.ACCEPT)s.offer(256);
        assertEquals(256,s.poll(2).mask());
        assertThrows(IllegalArgumentException.class,()->s.offer(4096));
    }
    @Test void nearbyRecipientsRequireEveryAuthorityCondition(){
        assertTrue(MdVisualInputState.recipient(true,true,true,true,1024));
        assertFalse(MdVisualInputState.recipient(true,true,true,true,1024.001));
        assertFalse(MdVisualInputState.recipient(true,true,true,true,Double.NaN));
        assertFalse(MdVisualInputState.recipient(true,true,true,true,-1));
        for(int missing=0;missing<4;missing++)assertFalse(MdVisualInputState.recipient(missing!=0,missing!=1,missing!=2,missing!=3,1));
    }
}
