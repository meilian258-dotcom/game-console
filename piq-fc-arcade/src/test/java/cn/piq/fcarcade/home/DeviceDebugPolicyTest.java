package cn.piq.fcarcade.home;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceDebugPolicyTest {
    @Test void refreshAndSingleFieldActionsAreValidButMixedWritesAreRejected() {
        assertTrue(DeviceDebugPolicy.validRequest(0,-1,-1,-1));
        for(int mode=0;mode<=4;mode++)assertTrue(DeviceDebugPolicy.validRequest(8,mode,-1,-1));
        for(int on=0;on<=1;on++){
            assertTrue(DeviceDebugPolicy.validRequest(8,-1,on,-1));
            assertTrue(DeviceDebugPolicy.validRequest(8,-1,-1,on));
        }
        assertFalse(DeviceDebugPolicy.validRequest(-1,-1,-1,-1));
        assertFalse(DeviceDebugPolicy.validRequest(0,5,-1,-1));
        assertFalse(DeviceDebugPolicy.validRequest(0,-2,-1,-1));
        assertFalse(DeviceDebugPolicy.validRequest(0,-1,2,-1));
        assertFalse(DeviceDebugPolicy.validRequest(0,-1,-1,2));
        assertFalse(DeviceDebugPolicy.validRequest(0,1,0,-1));
        assertFalse(DeviceDebugPolicy.validRequest(0,-1,0,1));
        assertFalse(DeviceDebugPolicy.validRequest(0,1,0,1));
    }
    @Test void creativeIsNotAnOperatorBypass() {
        assertTrue(DeviceDebugPolicy.canActivate(true,true,false,true,true,true,false,false));
        assertFalse(DeviceDebugPolicy.canActivate(true,true,false,true,false,true,false,false));
        assertFalse(DeviceDebugPolicy.canActivate(true,true,false,true,true,false,false,false));
        assertFalse(DeviceDebugPolicy.canActivate(true,true,true,true,true,true,false,false));
        assertFalse(DeviceDebugPolicy.canActivate(true,true,false,true,true,true,true,false));
        assertFalse(DeviceDebugPolicy.canActivate(true,true,false,true,true,true,false,true));
        assertFalse(DeviceDebugPolicy.canActivate(false,true,false,true,true,true,false,false));
        for(int mode=0;mode<=2;mode++)assertFalse(DeviceDebugPolicy.mayEdit(false,false,true,mode,-1,-1,7));
        assertFalse(DeviceDebugPolicy.mayEdit(false,false,true,-1,1,-1,7));
        assertFalse(DeviceDebugPolicy.mayEdit(false,false,true,-1,-1,1,7));
    }
    @Test void editsRespectBusyCapabilitiesAndRealOccupancySupport() {
        assertTrue(DeviceDebugPolicy.mayEdit(true,false,true,4,-1,-1,31));
        assertFalse(DeviceDebugPolicy.mayEdit(false,false,true,4,-1,-1,31));
        assertFalse(DeviceDebugPolicy.mayEdit(true,true,true,4,-1,-1,31));
        assertFalse(DeviceDebugPolicy.mayEdit(true,false,true,4,-1,-1,15));
        for(int mode=0;mode<=2;mode++){
            assertTrue(DeviceDebugPolicy.mayEdit(true,false,true,mode,-1,-1,7));
            assertFalse(DeviceDebugPolicy.mayEdit(true,true,true,mode,-1,-1,7));
            assertFalse(DeviceDebugPolicy.mayEdit(true,false,true,mode,-1,-1,7^(1<<mode)));
        }
        assertTrue(DeviceDebugPolicy.mayEdit(true,false,true,-1,0,-1,0));
        assertFalse(DeviceDebugPolicy.mayEdit(true,false,false,-1,0,-1,7));
        assertTrue(DeviceDebugPolicy.mayEdit(true,false,false,-1,-1,0,0));
        assertFalse(DeviceDebugPolicy.mayEdit(true,true,true,-1,-1,0,7));
    }
    @Test void reachIsBoundedByBothSixBlocksAndActualPlayerReach() {
        assertTrue(DeviceDebugPolicy.inRange(36,7));assertFalse(DeviceDebugPolicy.inRange(36.001,7));
        assertTrue(DeviceDebugPolicy.inRange(20.25,4.5));assertFalse(DeviceDebugPolicy.inRange(20.251,4.5));
        assertFalse(DeviceDebugPolicy.inRange(-1,6));assertFalse(DeviceDebugPolicy.inRange(Double.NaN,6));
        assertFalse(DeviceDebugPolicy.inRange(0,Double.POSITIVE_INFINITY));assertFalse(DeviceDebugPolicy.inRange(0,0));
    }
    @Test void missingExpiredAndReplacedGrantsNeverAuthorizeAnOldToolPacket() {
        UUID old=UUID.randomUUID(),fresh=UUID.randomUUID();
        assertTrue(DeviceDebugPolicy.matchesToken(old,old));
        assertFalse(DeviceDebugPolicy.matchesToken(old,null));
        assertFalse(DeviceDebugPolicy.matchesToken(old,fresh));
        assertFalse(DeviceDebugPolicy.matchesToken(null,old));
        assertFalse(DeviceDebugPolicy.matchesToken(null,null));
    }
}
