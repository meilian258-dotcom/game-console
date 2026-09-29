package cn.piq.sfchome.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcPublicDefaultsSourceTest {
    private String source(String type)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/server/"+type+".java"));}
    private String section(String source,String start,String end){int from=source.indexOf(start);assertTrue(from>=0);int to=source.indexOf(end,from);assertTrue(to>from);return source.substring(from,to);}
    @Test void onlyFreshPreviouslyUnconfiguredCardGetsTwoPlayerDefault()throws Exception{
        String write=section(source("SfcCartridgeEditorService"),"private static void writeRom(","private static boolean safeName(");
        assertTrue(write.contains("SfcCartridgeData.romSha(stack).isEmpty()&&!SfcCartridgeData.hasExplicitPlayerCount(stack)"));
        assertTrue(write.contains("SfcCartridgeData.write(stack,hash,title);if(fresh)SfcCartridgeData.setPlayers(stack,2)"));
        assertFalse(write.contains("setPlayers(stack,1)"));
    }
    @Test void newPublicRunAllowsJoiningWithoutTheFormerOfferDialog()throws Exception{
        String power=section(source("SfcHomeServer"),"private static boolean powerOn(","private static void sendRuntime(");
        assertTrue(power.contains("s.multiplayer=true"));assertFalse(power.contains("new SfcJoinNetwork.Offer"));
        assertTrue(power.contains("preflight(p,c,true)"));assertTrue(power.contains("!ItemStack.matches(card,c.insertedCartridge())"));
    }
    @Test void explicitCardLimitsAndStoredApprovalStillProtectAdmission()throws Exception{
        String source=source("SfcHomeServer");
        String request=section(source,"private static void requestJoin(","public static void decideJoin(");
        assertTrue(request.contains("requestedPort>=SfcCartridgeData.maxPlayers("));assertTrue(request.contains("if(!candidate(p,s,true))"));
        assertTrue(request.contains("if(!s.connection.console().joinApprovalRequired())"));
        assertTrue(request.contains("decideJoin(host,new SfcJoinNetwork.Decision(s.id,s.epoch,s.join.gate.token,true))"));
        assertTrue(request.contains("new SfcJoinNetwork.Approval"));
        String reset=section(source,"private static void reset(","private static boolean hostValid(");
        assertTrue(reset.contains("next.multiplayer=old.multiplayer"));
    }
}
