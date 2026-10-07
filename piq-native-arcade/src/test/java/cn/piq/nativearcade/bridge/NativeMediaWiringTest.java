package cn.piq.nativearcade.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Supplements executable buffer/diagnostic tests with the actual adapter/GUI call-site wiring. */
class NativeMediaWiringTest {
    private static String source(String file) throws Exception { return Files.readString(Path.of(file)); }
    @Test void onlyJniMediaUsesTheNewInputPolicy() throws Exception {
        String session = source("src/main/java/cn/piq/nativearcade/bridge/NativeJniMediaSession.java");
        assertTrue(session.contains("new NativeJniInputBuffer()"));
        assertTrue(session.contains("if(!closing.get()&&!inputs.offer(a,b,c,d))"));
        assertTrue(session.contains("failure=\"街机输入队列已满\";close();"));
        String helper = source("helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java");
        assertTrue(helper.contains("new NativeInputPorts()"));
        assertFalse(helper.contains("NativeJniInputBuffer"));
    }
    @Test void ownerMeasuresTheActualCoreRunAndReadSideNeverTouchesCore() throws Exception {
        String session = source("src/main/java/cn/piq/nativearcade/bridge/NativeJniMediaSession.java");
        int begin = session.indexOf("diagnostics.beginFrame()");
        int run = session.indexOf("core.run(", begin);
        int complete = session.indexOf("diagnostics.completedFrame(started)", run);
        assertTrue(begin >= 0 && run > begin && complete > run);
        assertTrue(session.contains("public List<String> diagnostics(){return diagnostics.lines();}"));
        String meter = source("src/main/java/cn/piq/nativearcade/bridge/NativeMediaDiagnostics.java");
        assertFalse(meter.contains("LibretroJniRuntime"));
        assertFalse(meter.contains("pollFrame("));
        assertFalse(meter.contains("nextFrame("));
    }
    @Test void existingAudioVideoPageUsesTheLocalEmulatorDiagnostics() throws Exception {
        String backend = source("src/main/java/cn/piq/nativearcade/client/NativeCabinetBackend.java");
        assertTrue(backend.contains("public List<String> diagnostics(){return core.diagnostics();}"));
        String host = source("../piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java");
        assertTrue(host.contains("if(!playing||emulator==null||!current())return List.of();"));
        assertTrue(host.contains("List.copyOf(emulator.diagnostics())"));
        String page = source("../piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetMediaTuning.java");
        assertTrue(page.contains("CabinetClientBackends.mediaDiagnostics()"));
        assertTrue(page.contains("Math.min(8,lines.size())"));
    }
    @Test void diagnosticTooltipUsesTheSameFrameSnapshotInsteadOfSamplingTwice() throws Exception {
        String screen = source("../piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSyncSettingsScreen.java");
        assertEquals(1, screen.split("CabinetMediaTuning\\.diagnostics\\(\\)", -1).length - 1);
        assertTrue(screen.contains("for(String line:diagnosticLines)"));
        assertTrue(screen.contains("Component.literal(diagnosticLines.get(line))"));
        assertTrue(screen.contains("if(line<diagnosticLines.size())"));
    }
}
