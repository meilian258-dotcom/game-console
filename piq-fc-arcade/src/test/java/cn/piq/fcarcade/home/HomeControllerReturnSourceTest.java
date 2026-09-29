package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards supplement the physical-count and lease identity tests. */
class HomeControllerReturnSourceTest {
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/"+name+".java"));}
    String section(String s,String from,String to){int begin=s.indexOf(from);assertTrue(begin>=0,from);int end=s.indexOf(to,begin);assertTrue(end>begin,to);return s.substring(begin,end);}
    @Test void dockReturnSupportsBothHandsWithExactConsolePortAndUniqueOwnedReceipt()throws Exception{
        String s=section(source("HomeControllerService"),"public static boolean returnHeldAtDock(","public static InteractionResult useOn(");
        for(String required:new String[]{"player.getItemInHand(candidate)","InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND", "physicalUsable(player,target)","lease.port() != port",
                "lease.console().equals(identity(target))","ownedItem(state,lease,player) != stack","!holds(player,stack)","release(player.getServer(),state,lease,true)"})assertTrue(s.contains(required),required);
        assertFalse(s.contains("borrowIdle("));assertFalse(s.contains("takeHomeController("));
    }
    @Test void dockReturnRunsAfterPermissionCallbacksAndShortCircuitsTakingAgain()throws Exception{
        String s=source("HomeApplianceService");
        assertTrue(s.indexOf("HomeControllerService.returnHeldAtDock(")>s.indexOf("Recompute authority, inventory and ray"));
        assertTrue(s.contains("!HomeControllerService.returnHeldAtDock(player,fc,control.port(),hand)"));
    }
    @Test void tossingNeverEntersTheOldWorldTransferRoute()throws Exception{
        String s=section(source("HomeControllerService"),"private static void tossed(","private static void recycleToss(");
        assertTrue(s.contains("HomeControllerInventory.removedForToss("));
        assertTrue(s.contains("release(player.getServer(), state, lease, true)"));
        assertTrue(s.contains("finally { recycleToss(event); }"));
        assertFalse(s.contains("ledger.toss("));assertFalse(s.contains("p2_transfer"));
    }
    @Test void poweredReturnOnlyDetachesTheControllerAndLeavesTheEmulatorRunning()throws Exception{
        String s=section(source("HomeControllerService"),"if(ServerArcadeSessions.isPoweredHomeSession(","if (lease.port() == 0)");
        assertTrue(s.contains("ServerArcadeSessions.releaseHomeController("));assertTrue(s.contains("revoke(server,state,lease)"));
        for(String forbidden:new String[]{"stopHomeControllerSession","closeSession","powerOff","reset("})assertFalse(s.contains(forbidden),forbidden);
    }
}
