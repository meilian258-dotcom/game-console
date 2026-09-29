package cn.piq.mdhome;

import cn.piq.fcarcade.home.HomeApplianceControl;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdControlsTest {
    @Test void visibleSliderIsReachableFromAbove(){
        assertEquals(HomeApplianceControl.POWER,MdControls.pick(new Vec3(10.15/16,1,8.04/16),new Vec3(10.15/16,0,8.04/16)));
    }
    @Test void resetAndCartridgeAreNotPower(){
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(5.8/16,1,8/16),new Vec3(5.8/16,0,8/16)));
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(.5,1,.3),new Vec3(.5,0,.3)));
    }
    @Test void rayMustActuallyReachButton(){
        assertEquals(HomeApplianceControl.NONE,MdControls.pick(new Vec3(10/16d,1,.5),new Vec3(10/16d,.8,.5)));
    }
}
