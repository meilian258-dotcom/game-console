package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.ControllerCableGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerCablePoseTest {
    @Test void firstPersonAnchorUsesRealExistingControllerRigForBothHandsAndAllSwings() {
        for(boolean right:new boolean[]{false,true})for(boolean two:new boolean[]{false,true})for(int i=0;i<=100;i++) {
            var rig=ControllerPoseLayout.first(right,two,0,i/100d);
            var grip=ControllerCableGeometry.firstGrip(rig.x(),rig.y(),rig.z(),rig.pitch(),rig.yaw());
            assertEquals(rig.x(),grip.x());
            assertEquals(.14,Math.hypot(grip.y()-rig.y(),grip.z()-rig.z()),1e-12);
            assertTrue(grip.z()<rig.z());assertTrue(grip.y()>rig.y());
            assertTrue(grip.z()>-1.9&&grip.z()<-1.5);assertTrue(grip.y()>-.63&&grip.y()<-.5);
            if(two)assertEquals(0,grip.x());else assertEquals(right?.34:-.34,grip.x());
        }
    }
    @Test void badRigNeverGeneratesNanCord() {
        assertNull(ControllerCableGeometry.firstGrip(Double.NaN,0,0,0,0));
        assertNull(ControllerCableGeometry.firstGrip(0,0,0,Double.POSITIVE_INFINITY,0));
    }
}
