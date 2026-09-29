package cn.piq.sfchome.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcControllerReturnSourceTest {
    String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/server/SfcHomeServer.java"));}
    String section(String s,String from,String to){int begin=s.indexOf(from);assertTrue(begin>=0,from);int end=s.indexOf(to,begin);assertTrue(end>begin,to);return s.substring(begin,end);}
    @Test void dockReturnRequiresOriginalPortAndPostCallbackLeaseValidation()throws Exception{
        String s=section(source(),"private static void claim(","private static void attachHostController(");
        String gate=section(s,"for(Lease l:st.leases.values())","if(s==null){");
        for(String check:new String[]{"l.console!=c","l.port!=requestedPort","!validLease(p,l,false)","!authorizedController(p,l)"})assertTrue(gate.contains(check),check);
        assertTrue(gate.indexOf("if(held(p,l)){returnController(p.getServer(),l);return;}")>gate.lastIndexOf("!validLease(p,l,false)"));
        assertTrue(gate.indexOf("returnController(")<gate.indexOf("reclaim(p,l)"));
    }
    @Test void dropUsesServerConnectionOwnerPortAndUniquePhysicalTokenThenCancelsTheWorldItem()throws Exception{
        String s=section(source(),"private static void tossedController(","/** Wired receipts never remain");
        for(String check:new String[]{"event.isCanceled()","lease.connection==player.connection.getConnection()",
                "lease.authority.accepts(player.getUUID()","lease.authority.itemMatches(","SfcControllerInventory.removedForToss(",
                "if(genuine)returnController(player.getServer(),lease)","finally{dropped.setCount(0);event.getEntity().discard();event.setCanceled(true);}"})assertTrue(s.contains(check),check);
        assertFalse(s.contains("grant("));assertFalse(s.contains("stop("));
    }
    @Test void returnRetainsSessionAndOtherPortWhileOnlySuccessfulExplicitReturnsMakeSound()throws Exception{
        String s=source(),release=section(s,"private static void release(MinecraftServer","private static void releaseConsole(");
        assertTrue(release.contains("detach(st,current,l,reason)"));assertTrue(release.contains("l.console.setControllerLeased(l.port,false)"));
        assertTrue(release.contains("removeControllerCopies(p,l.id)"));assertFalse(release.contains("stop("));assertFalse(release.contains("HomeInteractionSounds.play"));
        String explicit=section(s,"private static void returnController(","private static void tossedController(");
        assertTrue(explicit.contains("st.leases.get(lease.id)!=lease"));assertTrue(explicit.indexOf("HomeInteractionSounds.play")>explicit.indexOf("release(server,lease,"));
    }
    @Test void approvalPreferenceSkipsOnlyDialogAndUsesTheFullExistingAdmissionPipeline()throws Exception{
        String s=source(),request=section(s,"private static void requestJoin(","public static void decideJoin(");
        int automatic=request.indexOf("if(!s.connection.console().joinApprovalRequired())");
        assertTrue(automatic>request.indexOf("if(!s.multiplayer)"));assertTrue(automatic>request.indexOf("if(!candidate(p,s,true))"));
        assertTrue(automatic>request.indexOf("s.join=new Joining("));assertTrue(automatic<request.indexOf("new SfcJoinNetwork.Approval("));
        assertTrue(request.contains("decideJoin(host,new SfcJoinNetwork.Decision(s.id,s.epoch,s.join.gate.token,true))"));
        String decision=section(s,"public static void decideJoin(","private static void joinReady(");
        assertTrue(decision.contains("!joinValid(p.getServer(),s,true)"));assertTrue(decision.contains("!candidate(other,s,false)"));assertTrue(decision.contains("sendRuntime(other,s,j.second)"));
    }
}
