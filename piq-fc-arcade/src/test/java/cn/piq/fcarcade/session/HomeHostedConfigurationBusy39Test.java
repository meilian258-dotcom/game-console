package cn.piq.fcarcade.session;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level integration guards for the private Minecraft manager, without a fake world/server. */
class HomeHostedConfigurationBusy39Test {
    private static String source() throws Exception {return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/server/ServerArcadeSessions.java")).replaceAll("\\s+","");}
    private static String section(String text,String begin,String end){int first=text.indexOf(begin),last=text.indexOf(end,first);assertTrue(first>=0&&last>first,"Missing lifecycle boundary: "+begin);return text.substring(first,last);}

    @Test void closingGateMatchesExactHardwareAndDoesNotStopAtCoreTermination() throws Exception {
        String busy=section(source(),"publicstaticbooleanhomeConfigurationBusy(","publicstaticbooleanisPoweredHomeSession(");
        assertTrue(busy.contains("if(m.closingHosted.stream().anyMatch(s->s.hosted!=null&&console.hardwareId().equals(s.homeConsoleId)))returntrue;"),"Closing hardware remains busy independently of the removed active session");
        assertFalse(busy.contains("closingHosted.isEmpty()"),"Another device closing must not globally lock every home's configuration");
        assertFalse(busy.contains(".terminated()"),"A terminated core may still have a final server-thread snapshot to commit");
        assertFalse(busy.contains("homeRuntime.running()"),"Closing the logical runtime must not remove the physical hardware's busy gate");
    }
    @Test void closeTransfersToClosingBeforeRemovingActiveSession() throws Exception {
        String close=section(source(),"privatevoidclose(MinecraftServerserver,Sessionsession,booleanpersist)","privatevoidrefreshIdleScoreDisplays(");
        int closing=close.indexOf("closingHosted.add(session)"),removed=close.indexOf("sessions.remove(session.key)"),authorityClosed=close.indexOf("session.homeRuntime.close()");
        assertTrue(closing>=0&&removed>closing&&authorityClosed>closing,"There must be no untracked interval between active ownership and final-save cleanup");
    }
    @Test void finalSnapshotIsCollectedAndSavedBeforeClosingBusyEntryIsRemoved() throws Exception {
        String reap=section(source(),"privatevoidtick(MinecraftServerserver)","HomeControllerService.tick(server)");
        assertTrue(reap.contains("for(varstopped:List.copyOf(closingHosted))if(stopped.hosted.terminated()){collectHostedSnapshot(server,stopped);"));
        assertTrue(reap.contains("booleanclean=stopped.hosted.error()==null;booleansaved=clean&&stopped.saveMode==RomSaveMode.NONE;"),"A failed final capture must not report clean even if an earlier periodic snapshot exists");
        int save=reap.indexOf("if(stopped.hostedFinalPersist&&clean)saved=saveState(server,stopped)||saved;"),result=reap.indexOf("stopped.homeLaunch.finished(saved,"),remove=reap.indexOf("closingHosted.remove(stopped)");
        assertTrue(save>0&&result>save&&remove>result,"Commit permitted final snapshot and report actual result before releasing hardware busy state");
    }
}
