package cn.piq.fcarcade.client.cabinet;

import cn.piq.retro.api.RetroEmulator;
import cn.piq.retro.api.RetroFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetRetroCleanupTest {
    private static class Core implements RetroEmulator {
        final StringBuilder calls=new StringBuilder();boolean runtimeFailure,linkFailure;
        public boolean isReady(){return true;}public String error(){return null;}
        public void offerInput(int a,int b){}public RetroFrame pollFrame(){return null;}
        public void clearInput(){calls.append('C');if(runtimeFailure)throw new IllegalStateException();if(linkFailure)throw new UnsatisfiedLinkError();}
        public void close(){calls.append('X');if(runtimeFailure)throw new IllegalStateException();if(linkFailure)throw new UnsatisfiedLinkError();}
    }
    @Test void nullCallsKeepOldSourceSignatureUnambiguous(){
        assertDoesNotThrow(()->CabinetCleanup.close(null));
        assertDoesNotThrow(()->CabinetCleanup.closeRetro(null));
    }
    @Test void sharedCleanupClearsThenCloses(){var core=new Core();CabinetCleanup.closeRetro(core);assertEquals("CX",core.calls.toString());}
    @Test void runtimeFailureCannotPreventCloseOrEscapeHostCleanup(){var core=new Core();core.runtimeFailure=true;assertDoesNotThrow(()->CabinetCleanup.closeRetro(core));assertEquals("CX",core.calls.toString());}
    @Test void linkageFailureCannotPreventCloseOrEscapeHostCleanup(){var core=new Core();core.linkFailure=true;assertDoesNotThrow(()->CabinetCleanup.closeRetro(core));assertEquals("CX",core.calls.toString());}
}
