package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards complement real transfer/lease/worker tests; not a Minecraft socket test. */
class HostedAuthorizationSourceTest {
    private static String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade",path+".java"));}
    @Test void hostedReopenStillNeedsManifestBoundGrantButNoDownload()throws Exception{
        String s=source("client/cabinet/CabinetSharedGames");
        s=s.substring(s.indexOf("public static void authorizeHosted("),s.indexOf("private static Path cacheRoot()"));
        assertTrue(s.contains("CabinetGameNetwork.OPEN,null"));assertTrue(s.contains("CabinetGameNetwork.END,null"));
        assertTrue(s.contains("manifest.equals(ended.manifest())"));assertTrue(s.contains("t.check();RESOLVED.put"));
        assertTrue(s.contains("if(!complete)t.abort()"));assertTrue(s.contains("WORKER.release()"));
        for(String forbidden:new String[]{"CabinetGameNetwork.GET","download(","cacheRoot()","Files.","factory.open"})assertFalse(s.contains(forbidden),forbidden);
        String c=source("client/cabinet/CabinetClientBackends");c=c.substring(c.indexOf("private static void startHosted("));
        assertTrue(c.contains("if(remember)CabinetSharedGames.resolve(request,chosen,true,provider,key,connection)"));
        assertTrue(c.indexOf("authorizeHosted")<c.indexOf("new CabinetRoomNetwork.Ready"));assertFalse(c.contains("factory.open"));
    }
    @Test void hostedPendingLaunchAlreadyLocksGameConfiguration()throws Exception{
        String s=source("cabinet/CabinetRooms");s=s.substring(s.indexOf("public static boolean canConfigureGame("),s.indexOf("static CabinetRoomLedger.Room<CabinetTarget> syncRoom"));
        assertTrue(s.contains("CabinetHostedSessions.get(player.getServer(),room.id)==null"));
        assertTrue(s.contains("validateLease(player,lease,ResourceLocation.parse(room.backend))!=null"));
    }
}
