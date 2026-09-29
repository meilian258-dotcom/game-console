package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards for physical loans independent of runtime; actual ItemStack/NBT probe is separate. */
class SfcOfflineControllerSourceTest {
    String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/server/SfcHomeServer.java"));}
    String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b,s.indexOf(a)));}
    @Test void offlineDockOnlyBorrowsPhysicalControllerAfterPermissionAndRuntimeRecheck()throws Exception{
        String s=source(),claim=between(s,"private static void claim(","private static void attachHostController(");
        assertTrue(s.contains("onControllerDock(ServerPlayer p,ExternalHomeConsoleBlockEntity c,int port)"));
        String off=between(claim,"if(s==null){","if(s.clock==null){feedback(p,\"请等待主机就绪\")");
        assertTrue(off.contains("!physicalControllerAllowed(p,c)||st.sessions.containsKey(c.hardwareId())"));assertTrue(off.contains("grant(p,c,requestedPort,st)"));
        for(String forbidden:new String[]{"new Session","sendRuntime(","powerOn(","preflight(","SfcHomeNetwork.send"})assertFalse(off.contains(forbidden),forbidden);
    }
    @Test void poweredOffReceiptsRemainUniquePerPlayerAndPhysicalPort()throws Exception{
        String grant=between(source(),"private static Lease grant(","public static void useController(");
        assertTrue(grant.contains("l.player.equals(p.getUUID())||l.console==c&&l.port==port"));assertTrue(grant.contains("!cableReach(p,c)"));
    }
    @Test void poweringOnCanUseOnlyHostsAlreadyOwnedCurrentConsoleLoan()throws Exception{
        String start=between(source(),"private static boolean powerOn(","private static void sendRuntime(");
        assertTrue(start.contains("l.player.equals(p.getUUID())&&l.console!=c"));assertTrue(start.contains("new Lease[2]"));
        assertTrue(start.contains("Lease borrowed=playerLease(st,p.getUUID())"));assertTrue(start.contains("borrowed.console==c)attachHostController(p,s,borrowed)"));
        assertFalse(start.contains("sendRuntime(other"));assertFalse(start.contains("st.leases.values().forEach"));
    }
    @Test void hostPhysicalActivationRechecksIdentityAndBothEndpointsAfterPermissionCallbacks()throws Exception{
        String attach=between(source(),"private static void attachHostController(","private static Lease playerLease(");
        assertEquals(2,attach.split("hostValid\\(p,s\\)",-1).length-1);assertEquals(2,attach.split("endpointFacts\\(p,lease,s.connection\\)",-1).length-1);
        assertTrue(attach.lastIndexOf("st.sessions.get(s.connection.consoleId())!=s")>attach.indexOf("authorizedStart(p,lease,s.connection)"));
        assertTrue(attach.indexOf("new SfcHomeNetwork.Control")>attach.indexOf("s.ports[lease.port]=lease"));
    }
    @Test void preborrowedRemoteStillRequiresHostApprovalAndExactOriginalPort()throws Exception{
        String s=source(),request=between(s,"private static void requestJoin(","public static void decideJoin(");
        assertTrue(request.contains("reserved.port!=requestedPort"));assertTrue(request.contains("if(!s.multiplayer)"));assertTrue(request.contains("new SfcJoinNetwork.Approval"));
        String approve=between(s,"public static void decideJoin(","private static void joinReady(");
        assertTrue(approve.indexOf("j.gate.approve")<approve.indexOf("j.second=playerLease(st,other.getUUID())"));
        assertTrue(approve.contains("j.second.port!=j.port||!validLease(other,j.second,false)"));assertFalse(approve.contains("s.ports[j.port]="));
    }
    @Test void shutdownAndCancelledJoinRemoveRuntimeWithoutDestroyingPhysicalReceipts()throws Exception{
        String s=source(),stop=between(s,"private static void stop(","private static void detach(");
        assertTrue(stop.contains("st.sessions.remove(console)"));assertTrue(stop.contains("input.clear()"));assertTrue(stop.contains("new SfcHomeNetwork.Stopped"));
        for(String forbidden:new String[]{"release(server","st.leases.remove","setCount(0)","setControllerLeased"})assertFalse(stop.contains(forbidden));
        String abort=between(s,"private static void abortJoin(","public static void ready(");assertFalse(abort.contains("release(server"));assertTrue(abort.contains("st.transfer=null"));
        String explicit=between(s,"private static void release(MinecraftServer","private static void releaseConsole(");
        assertTrue(explicit.contains("abortJoin(st,current,reason);detach"));assertTrue(explicit.contains("removeControllerCopies(p,l.id)"));
    }
    @Test void idleReceiptDoesNotClaimInputOrSpectatorExclusionAndBodyCanReturnWithoutTv()throws Exception{
        String s=source(),watch=between(s,"static boolean watchParticipant(","public static void register(");
        assertFalse(watch.contains("state.leases.values()"));assertTrue(watch.contains("for(Lease lease:session.ports)"));
        String input=between(s,"public static void joinInput(","private static void acceptInput(");assertTrue(input.contains("int port=port(s,p.getUUID())"));assertTrue(input.contains("if(port<0||!s.ports[port].id.equals(packet.lease()))return"));
        String on=between(s,"public static InteractionResult useControllerOn(","private static String returnMessage");
        assertTrue(on.indexOf("clicked==lease.console&&!st.sessions.containsKey")<on.indexOf("HomeSystems.connection(level,lease.console.getBlockPos())"));
        assertTrue(on.contains("physicalControllerAllowed(p,lease.console)||!validLease(p,lease,true)"));
    }
}
