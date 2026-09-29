package cn.piq.sfchome.client;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetworkDiagnosticsSourceTest {
    @Test void addonReportsAdmittedModeWithConnectionAndDimensionChecksWithoutChangingRuntime()throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        assertTrue(source.contains("registerDevices(\"sfc-home\",SfcHomeClient::diagnosticDevices)"));
        int start=source.indexOf("diagnosticDevices(){"),end=source.indexOf("static boolean sessionCurrent",start);
        String method=source.substring(start,end);
        assertTrue(method.contains("currentSession()"));assertTrue(method.contains("sessionCurrent(s,mc.getConnection())"));
        assertTrue(method.contains("s.dimension().equals(mc.level.dimension().location())"));assertTrue(method.contains("s.serverHosted()"));
        assertFalse(method.contains("synchronizationMode()"));assertFalse(method.contains("sendInput("));assertFalse(method.contains("PacketDistributor"));
    }
}
