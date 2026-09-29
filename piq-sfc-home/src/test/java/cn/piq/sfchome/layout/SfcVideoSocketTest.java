package cn.piq.sfchome.layout;
import cn.piq.fcarcade.home.ApplianceRay;
import cn.piq.fcarcade.home.HomeApplianceControl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcVideoSocketTest {
    static ApplianceRay.Point p(double x,double y,double z){return new ApplianceRay.Point(x/16,y/16,z/16);}
    @Test void scaledMultiOutSocketMatchesRearMeshNotFront(){
        assertEquals(HomeApplianceControl.VIDEO_DISCONNECT,SfcApplianceControls.hitPoints(p(4.325,1.0425,35),p(4.325,1.0425,17)));
        assertNotEquals(HomeApplianceControl.VIDEO_DISCONNECT,SfcApplianceControls.hitPoints(p(4.325,1.0425,-10),p(4.325,1.0425,35)));
    }
    @Test void allRotationsAndAdjacentCaseMiss(){
        for(int turn=0;turn<4;turn++){
            var a=ApplianceRay.unrotate(p(4.325,1.0425,35),4-turn);var b=ApplianceRay.unrotate(p(4.325,1.0425,17),4-turn);
            assertEquals(HomeApplianceControl.VIDEO_DISCONNECT,SfcApplianceControls.hitPoints(ApplianceRay.unrotate(a,turn),ApplianceRay.unrotate(b,turn)));
        }
        assertNotEquals(HomeApplianceControl.VIDEO_DISCONNECT,SfcApplianceControls.hitPoints(p(7,1,35),p(7,1,17)));
    }
}
