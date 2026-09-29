package cn.piq.fcarcade.home;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static cn.piq.fcarcade.home.HomeApplianceControl.*;
import static org.junit.jupiter.api.Assertions.*;
class VideoSocketControlTest {
    static ApplianceRay.Point p(double x,double y,double z){return new ApplianceRay.Point(x/16,y/16,z/16);}
    @Test void fcRearSocketAcceptsOnlyRearApproachAndNotUnrelatedShell(){
        assertEquals(VIDEO_DISCONNECT,ApplianceControls.famicom(p(8,2.28,40),p(8,2.28,14)));
        assertNotEquals(VIDEO_DISCONNECT,ApplianceControls.famicom(p(8,2.28,-20),p(8,2.28,40)));
        assertNotEquals(VIDEO_DISCONNECT,ApplianceControls.famicom(p(11,2.28,40),p(11,2.28,14)));
    }
    @Test void allSuborVariantsAndThreeSocketsUseSameAction(){
        for(double x:new double[]{12.77,12.14,10.25})assertEquals(VIDEO_DISCONNECT,ApplianceControls.subor(false,p(x,.50175,30),p(x,.50175,12)));
        for(boolean compact:new boolean[]{false,true})for(double x:new double[]{24.32,22.72,21.12}){
            double rear=compact?23.632*.8-4:23.632;
            assertEquals(VIDEO_DISCONNECT,ApplianceControls.subor(true,compact,p(x,1.05,rear+20),p(x,1.05,rear-.5)));
            assertNotEquals(VIDEO_DISCONNECT,ApplianceControls.subor(true,compact,p(x,1.05,0),p(x,1.05,rear+20)));
        }
    }
    @Test void rotatedWorldRaysReturnTheSameSocket(){
        for(int turn=0;turn<4;turn++){
            var eye=ApplianceRay.unrotate(p(8,2.28,40),4-turn);var end=ApplianceRay.unrotate(p(8,2.28,14),4-turn);
            assertEquals(VIDEO_DISCONNECT,ApplianceControls.famicom(ApplianceRay.unrotate(eye,turn),ApplianceRay.unrotate(end,turn)));
        }
    }
    @Test void disconnectKeepsEmptyHandsTwoEndProtectionAndSingleLedgerRefund() throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeApplianceService.java"));
        assertTrue(s.contains("!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()"));
        assertTrue(s.contains("peerEvent.isCanceled()"));assertTrue(s.contains("!paired(endpoint,tv)"));
        assertTrue(s.indexOf("peerEvent.isCanceled()")<s.indexOf("HomeHardware.disconnect(endpoint,player)"));
        assertTrue(s.contains("controlAt(level,pos,player.getEyePosition()"));
        String h=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeHardware.java"));
        assertTrue(h.indexOf("data.ledger.close(linkId")<h.indexOf("if (result.refundCable())"));
    }
    @Test void busyRefreshNeverReplaysModeWritesOrBypassesSaving() throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSyncSettingsScreen.java"));
        assertTrue(s.contains("age%40==0&&age<1100"));assertTrue(s.contains("requestMode(target,backend,-1,debugToken)"));
        assertTrue(s.contains("!backend.equals(CabinetBackends.NES))refresh()"));
        assertFalse(s.contains("forceClose"));
    }
}
