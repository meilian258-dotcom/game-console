package cn.piq.sfchome.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks public execution wiring, not real Minecraft start/stop or core behavior. */
class SfcLegacySyncRetirementSourceTest {
    private String read(String name)throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));
    }
    @Test void publicPowerAndRuntimeRejectOldLocalBeforeLaunchAndKeepActualJniIdentity()throws Exception {
        String server=read("server/SfcHomeServer");
        String gate="HomeSyncPolicy.publicRunnable(c.synchronizationMode(),c.netplayJniTrial())";
        String support=server.substring(server.indexOf("private static boolean modeSupported("),server.indexOf("private static SfcHostedWorker openHosted("));
        assertTrue(support.contains(gate));
        assertFalse(support.contains("netplayExperimental()"),"SFC must not invent a RetroArch implementation");
        String power=server.substring(server.indexOf("private static boolean powerOn("),server.indexOf("private static boolean beginRuntime("));
        assertTrue(power.indexOf(gate)>=0&&power.indexOf(gate)<power.indexOf("new SfcNetplaySaveCatalog("));
        assertTrue(power.indexOf(gate)<power.indexOf("HomeLaunchServer.start("));
        assertTrue(power.contains("HomeSyncPolicy.legacyUnavailable()"));
        assertTrue(power.contains("c.netplayJniTrial()==netplay&&c.secondPortAllowed()==secondPort&&modeSupported(c)"));
        String begin=server.substring(server.indexOf("private static boolean beginRuntime("),server.indexOf("private static void sendRuntime("));
        assertTrue(begin.indexOf(gate)>=0&&begin.indexOf(gate)<begin.indexOf("new Session("));
        assertTrue(begin.contains("c.synchronizationMode()!=mode||c.netplayJniTrial()!=requestedNetplay||!modeSupported(c)"));
        assertTrue(server.contains("if(mode==1)return HomeSyncPolicy.legacyUnavailable()"));
    }
    @Test void coreAndStoredNetplayFlagsAreNotMigratedByRetirement()throws Exception {
        String settings=read("world/SfcNetplaySettings");
        assertTrue(settings.contains("String key = tag.contains(JNI) ? JNI : LEGACY"));
        assertTrue(settings.contains("tag.putBoolean(JNI, jni)"));
        assertTrue(settings.contains("tag.putBoolean(LEGACY, jni)"));
        assertFalse(settings.contains("remove("));
        assertFalse(settings.contains("putString("));
    }
}
