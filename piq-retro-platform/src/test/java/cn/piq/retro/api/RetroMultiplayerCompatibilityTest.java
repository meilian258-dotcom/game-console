package cn.piq.retro.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RetroMultiplayerCompatibilityTest {
    static final class Legacy implements RetroEmulator{
        int p1,p2,cleared;
        public boolean isReady(){return true;}public String error(){return null;}
        public void offerInput(int a,int b){p1=a;p2=b;}public void clearInput(){cleared++;}
        public RetroFrame pollFrame(){return null;}public void close(){}
    }
    @Test void oldTwoPortImplementationStillRunsWithoutNewOverrides(){var legacy=new Legacy();assertEquals(2,legacy.maxPlayers());legacy.offerInputs(1,256,0,0);assertEquals(1,legacy.p1);assertEquals(256,legacy.p2);}
    @Test void extraPortsCannotBeSilentlyLost(){var legacy=new Legacy();assertThrows(IllegalArgumentException.class,()->legacy.offerInputs(1,2,4,0));assertThrows(IllegalArgumentException.class,()->legacy.offerInputs(1,2,0,8));assertEquals(0,legacy.p1);}
    @Test void unsupportedPortReleaseCannotAccidentallyReleaseEveryPlayer(){var legacy=new Legacy();assertThrows(UnsupportedOperationException.class,()->legacy.releasePort(1));assertEquals(0,legacy.cleared);}
    @Test void oldImplementationHasEmptyReadOnlyDiagnostics(){var legacy=new Legacy();assertTrue(legacy.diagnostics().isEmpty());assertEquals(0,legacy.p1);assertEquals(0,legacy.p2);assertEquals(0,legacy.cleared);}
}
