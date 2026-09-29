package cn.piq.sfchome.layout;

import cn.piq.fcarcade.home.ApplianceRay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.home.HomeApplianceControl.*;

class SfcApplianceControlsTest {
    private static ApplianceRay.Point p(double x,double y,double z) { return new ApplianceRay.Point(x/16,y/16,z/16); }
    @Test void powerAndResetUseScaledOriginalMeshCoordinates() {
        var power=SfcConsoleScale.console(10.01,2.4,8.6125);
        var reset=SfcConsoleScale.console(5.99,2.4,8.6125);
        assertEquals(POWER,SfcApplianceControls.hitPoints(p(power.x(),30,power.z()),p(power.x(),0,power.z())));
        assertEquals(RESET,SfcApplianceControls.hitPoints(p(reset.x(),30,reset.z()),p(reset.x(),0,reset.z())));
        assertEquals(NONE,SfcApplianceControls.hitPoints(p(power.x(),-5,power.z()),p(power.x(),30,power.z())));
        assertEquals(NONE,SfcApplianceControls.hitPoints(p(reset.x(),-5,reset.z()),p(reset.x(),30,reset.z())));
    }
    @Test void dockedPadsFollowDockTranslationNotConsoleScale() {
        for(int port=0;port<2;port++) {
            var b=SfcConsoleScale.pad(port,0);
            double x=(b.minX()+b.maxX())/2,z=(b.minZ()+b.maxZ())/2;
            assertEquals(port==0?CONTROLLER_ONE:CONTROLLER_TWO,SfcApplianceControls.hitPoints(p(x,30,z),p(x,-1,z)));
        }
    }
    @Test void centerBodyAndCartridgeAreNotButtons() {
        assertEquals(NONE,SfcApplianceControls.hitPoints(p(8,30,10),p(8,-1,10)));
        assertEquals(NONE,SfcApplianceControls.hitPoints(p(8,30,12.2),p(8,-1,12.2)));
        assertEquals(NONE,SfcApplianceControls.hitPoints(new ApplianceRay.Point(Double.NaN,1,1),p(0,0,0)));
    }
}
