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
    @Test void newPublicRunUsesSharedExplicitConsentNotTheFormerOfferDialog()throws Exception{
        String power=section(source("SfcHomeServer"),"private static boolean powerOn(","private static void sendRuntime(");
        assertTrue(power.contains("s.multiplayer=allowSecond"));assertTrue(power.contains("HomeLaunchServer.start"));assertTrue(power.contains("launch.allowSecondPort()"));assertFalse(power.contains("new SfcJoinNetwork.Offer"));
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
    /** Caller wiring guard; actual DISABLED/release behavior is tested in NetplaySaveSessionTest. */
    @Test void noSaveRunDoesNotRequireTheReleasedSaveBinding()throws Exception{
        String source=source("SfcHomeServer");
        String ready=section(source,"private static void startClockIfReady(","private static boolean controlLease(");
        assertTrue(ready.contains("run!=null&&s.saveEnabled&&!NetplaySaveServer.activate"));
        String stop=section(source,"private static void stop(","private static void detach(");
        assertTrue(stop.contains("if(run!=null&&s.saveEnabled)"));
        assertTrue(stop.contains("boolean noServerSave=run==null||!s.saveEnabled"));
        assertTrue(stop.contains("s.flow.finished(noServerSave"));
    }
    @Test void applianceStaysBusyUntilSaveLeaseActuallyFinishes()throws Exception{
        String source=source("SfcHomeServer");
        String running=section(source,"@Override public boolean isRunning(","@Override public boolean synchronizationSettingsAvailable(");
        assertTrue(running.contains("st.stopping.get(c.hardwareId())"));
        String finish=section(source,"awaiting=NetplaySaveServer.awaitFinish(","retireNetplay(s)");
        assertTrue(finish.contains("st.stopping.remove(console,s)"));
        assertTrue(finish.contains("HomeApplianceService.refresh(s.connection.level(),s.connection.television().getBlockPos())"));
        String hosted=section(source,"Session ending=entry.getValue()","if(st.tick%200");
        assertTrue(hosted.contains("HomeApplianceService.refresh(ending.connection.level(),ending.connection.television().getBlockPos())"));
    }
}
