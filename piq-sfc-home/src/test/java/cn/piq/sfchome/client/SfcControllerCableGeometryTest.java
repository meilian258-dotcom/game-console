package cn.piq.sfchome.client;

import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.ControllerCableGeometry.Style;
import cn.piq.sfchome.layout.SfcConsoleScale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcControllerCableGeometryTest {
    @Test void sharedCableOriginExactlyMatchesActualProductionScaledConsoleCordEnd() {
        for(int port=0;port<2;port++)for(int turn=0;turn<4;turn++) {
            var end=SfcConsoleScale.console(port==0?10.025:5.975,.7225,5.275);
            var real=SfcConsoleScale.rotate(end.x(),end.y(),end.z(),turn);
            var shared=ControllerCableGeometry.socket(Style.SFC,port,turn);
            assertEquals(real.x()/16,shared.x(),1e-12);assertEquals(real.y()/16,shared.y(),1e-12);assertEquals(real.z()/16,shared.z(),1e-12);
        }
    }
    @Test void sharedCameraGripMatchesSfcTwoHandRigNotOldOnePointFiveControllerScale() {
        for(int i=0;i<=100;i++) {
            var rig=SfcControllerPoseLayout.rig(0,i/100d);
            var grip=ControllerCableGeometry.firstGrip(0,rig.y(),rig.z(),rig.pitch(),0);
            assertEquals(.14,Math.hypot(grip.y()-rig.y(),grip.z()-rig.z()),1e-12);
            assertTrue(grip.z()< -1.5&&grip.z()>-1.7);
        }
    }
}
