package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ControllerButtonAnimationTest {
    private final UUID lease=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void realMaskDepressesAndReleaseReturnsWithoutOvershoot() {
        var animation=new ControllerButtonAnimation();
        animation.update(lease,8,0,0,1,true,1_000_000_000L);
        animation.update(lease,8,0,0,1,true,1_040_000_000L);assertEquals(1,animation.value(1));
        animation.update(lease,8,0,0,0,true,1_060_000_000L);assertTrue(animation.value(1)>0&&animation.value(1)<1);
        animation.update(lease,8,0,0,0,true,1_160_000_000L);assertEquals(0,animation.value(1));
    }
    @Test void oppositeDirectionsCancelAndBothAxesSupportDiagonals() {
        var animation=new ControllerButtonAnimation();
        animation.update(lease,8,1,1,16|128,true,1_000_000_000L);
        animation.update(lease,8,1,1,16|128,true,1_040_000_000L);
        assertEquals(-6,animation.pitch());assertEquals(6,animation.yaw());
        animation.update(lease,8,1,1,240,true,1_140_000_000L);
        assertEquals(0,animation.pitch());assertEquals(0,animation.yaw());
    }
    @Test void pauseFocusLossReturnAndHandOrLeaseChangesClearStaleButtons() {
        var animation=new ControllerButtonAnimation();
        animation.update(lease,8,0,0,255,true,1_000_000_000L);
        animation.update(lease,8,0,0,255,false,1_020_000_000L);
        for(int bit=0;bit<8;bit++)assertEquals(0,animation.value(1<<bit));
        animation.update(lease,8,0,0,255,true,1_100_000_000L);
        animation.update(lease,8,0,1,255,true,1_120_000_000L);
        assertEquals(0,animation.value(1));assertFalse(animation.matches(lease,8,0,0));
        assertTrue(animation.matches(lease,8,0,1));
        animation.update(UUID.randomUUID(),8,0,1,0,true,1_140_000_000L);
        assertFalse(animation.matches(lease,8,0,1));
    }
    @Test void maskPulsesReboundRatherThanInventingContinuousTurboInput() {
        var animation=new ControllerButtonAnimation();long time=1_000_000_000L;
        for(int pulse=0;pulse<6;pulse++) {
            animation.update(lease,8,0,0,3,true,time);time+=40_000_000L;
            animation.update(lease,8,0,0,3,true,time);assertEquals(1,animation.value(1));
            time+=100_000_000L;animation.update(lease,8,0,0,0,true,time);
            assertEquals(0,animation.value(1));assertEquals(0,animation.value(2));time+=40_000_000L;
        }
    }
    @Test void invalidBindingCannotActivateAndBackwardsClockIsBounded() {
        var animation=new ControllerButtonAnimation();animation.update(null,8,0,0,255,true,9);
        assertEquals(0,animation.value(1));animation.update(lease,8,0,0,1,true,100);
        double before=animation.value(1);animation.update(lease,8,0,0,1,true,90);assertEquals(before,animation.value(1));
    }
    public static void main(String[] args)throws Exception {var test=new ControllerButtonAnimationTest();int n=0;for(var m:ControllerButtonAnimationTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(test);n++;}System.out.println("Passed "+n+" controller animation checks.");}
}
