package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetUseLatchTest {
    @Test void fastAssignmentAndLaunchAreAcceptedWhilePowerKeyIsHeld(){
        var latch=new CabinetUseLatch();
        for(int cycle=0;cycle<10;cycle++){
            latch.powerPress(true);latch.observe(true);
            assertTrue(latch.blocksInput());assertFalse(latch.blocksReply()); // Assignment then Launch.
            latch.observe(false);assertFalse(latch.blocksInput());assertFalse(latch.blocksReply());
            latch.powerPress(true);latch.exited(true); // Closed still stops stale reopen.
            assertTrue(latch.blocksInput());assertTrue(latch.blocksReply());
            latch.observe(false);assertFalse(latch.blocksInput());assertFalse(latch.blocksReply());
        }
    }
    @Test void genuineExitStillRejectsRepliesAndMouseReleaseOrWorldExitClearsBoth(){
        var latch=new CabinetUseLatch();latch.exited(true);latch.powerPress(true);
        assertTrue(latch.blocksReply());latch.observe(false);assertFalse(latch.blocksReply());assertFalse(latch.blocksInput());
        latch.powerPress(false);latch.exited(false);assertFalse(latch.blocksInput());
    }
    @Test void productionWiringSeparatesInputAndReplyGates()throws Exception{
        var source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java"));
        var press=source.substring(source.indexOf("public static void useKey("),source.indexOf("public static void screenOpening("));
        assertTrue(press.contains("CabinetUseGuard.inputBlocked()"));assertTrue(press.contains("CabinetUseGuard.suppressPowerRepeats()"));
        assertFalse(press.contains("CabinetUseGuard.suppressWhileHeld()"));
        assertTrue(source.contains("if(CabinetUseGuard.blocked()){release(request.lease());return;}"));
        assertTrue(source.contains("else startGame(null,false)")); // Existing authoritative game selection, not a new picker.
    }
}
