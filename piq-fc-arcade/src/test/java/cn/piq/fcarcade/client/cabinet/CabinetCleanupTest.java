package cn.piq.fcarcade.client.cabinet;
import cn.piq.fcarcade.cabinet.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetCleanupTest {
    static class Core implements CabinetEmulator{
        int cleared,closed;boolean clearFailure,linkFailure,closeFailure;
        public boolean isReady(){return true;}public String error(){return null;}
        public void offerInput(int a,int b){}public CabinetFrame pollFrame(){return null;}
        public void clearInput(){cleared++;if(clearFailure)throw new IllegalStateException("clear failed");if(linkFailure)throw new UnsatisfiedLinkError("backend gone");}
        public void close(){closed++;if(closeFailure)throw new IllegalStateException("close failed");}
    }
    @Test void nullCleanupIsSafe(){assertDoesNotThrow(()->CabinetCleanup.close(null));}
    @Test void normalCleanupClearsBeforeClose(){var c=new Core();CabinetCleanup.close(c);assertEquals(1,c.cleared);assertEquals(1,c.closed);}
    @Test void failingReleaseStillClosesNativeCore(){var c=new Core();c.clearFailure=true;assertDoesNotThrow(()->CabinetCleanup.close(c));assertEquals(1,c.closed);}
    @Test void missingBackendAndCloseFailureDoNotAbortHostCleanup(){var c=new Core();c.linkFailure=true;c.closeFailure=true;assertDoesNotThrow(()->CabinetCleanup.close(c));assertEquals(1,c.cleared);assertEquals(1,c.closed);}
}
