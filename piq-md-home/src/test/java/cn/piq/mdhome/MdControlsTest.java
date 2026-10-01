package cn.piq.mdhome;

import cn.piq.fcarcade.home.HomeApplianceControl;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdControlsTest {
    @Test void visibleSliderIsReachableFromAbove(){
        assertEquals(HomeApplianceControl.POWER,MdControls.pick(new Vec3(10.15/16,1,8.04/16),new Vec3(10.15/16,0,8.04/16)));
    }
    @Test void resetHasItsOwnActionAndBlankShellDoesNotPowerOn(){
        assertEquals(HomeApplianceControl.RESET,MdControls.pick(new Vec3(5.8/16,1,8/16d),new Vec3(5.8/16,0,8/16d)));
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(.5,1,.3),new Vec3(.5,0,.3)));
    }
    @Test void rayMustActuallyReachButton(){
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(10/16d,1,.5),new Vec3(10/16d,.8,.5)));
    }
    @Test void eachControllerAndRearVideoPortHasAnIndependentHotspot(){
        assertEquals(HomeApplianceControl.CONTROLLER_ONE,MdControls.pick(new Vec3(11/16d,1,2/16d),new Vec3(11/16d,0,2/16d)));
        assertEquals(HomeApplianceControl.CONTROLLER_TWO,MdControls.pick(new Vec3(5/16d,1,2/16d),new Vec3(5/16d,0,2/16d)));
        assertEquals(HomeApplianceControl.VIDEO_DISCONNECT,MdControls.pick(new Vec3(6.45/16,1.23/16,2),new Vec3(6.45/16,1.23/16,.8)));
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(-1,1,.5),new Vec3(-1,0,.5)));
    }
    @Test void nearestControlWinsWhenOneRayCrossesBothDocks(){
        assertEquals(HomeApplianceControl.CONTROLLER_ONE,MdControls.pick(new Vec3(1,.04,.1),new Vec3(0,.04,.1)));
        assertEquals(HomeApplianceControl.CONTROLLER_TWO,MdControls.pick(new Vec3(0,.04,.1),new Vec3(1,.04,.1)));
    }
    @Test void caseOccludesRearAvFromFrontAndInvalidRaysFailClosed(){
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(6.45/16,1.23/16,.3),new Vec3(6.45/16,1.23/16,2)));
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(Double.NaN,1,.5),new Vec3(.5,0,.5)));
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(Vec3.ZERO,Vec3.ZERO));
    }
    @Test void separateOutlineLeavesSpaceAboveEmptyFrontAndRotatesWithEveryHotspot(){
        for(int turn=0;turn<4;turn++){
            var shape=MdOutline.shape(true,turn);
            assertTrue(shape.max(net.minecraft.core.Direction.Axis.Y)<.3);
            var pad=MdOutline.rotate(MdControls.P1,turn).getCenter();
            var restored=cn.piq.fcarcade.home.ApplianceRay.unrotate(new cn.piq.fcarcade.home.ApplianceRay.Point(pad.x,pad.y,pad.z),turn);
            assertTrue(MdControls.P1.contains(new Vec3(restored.x(),restored.y(),restored.z())));
            assertEquals(HomeApplianceControl.CONTROLLER_ONE,MdControls.pick(new Vec3(restored.x(),1,restored.z()),new Vec3(restored.x(),0,restored.z())));
            assertEquals(MdOutline.shape(false,turn).bounds(),MdOutline.shape(false,turn+4).bounds());
        }
        assertFalse(MdOutline.shape(false,0).toAabbs().stream().anyMatch(b->b.contains(new Vec3(.5,.12,.3))));
    }
}
