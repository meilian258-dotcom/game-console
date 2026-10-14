package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Companion wiring guard; SfcNetplayActivationTest executes the worker/cleanup contract. */
class SfcFailureNoticeSourceTest {
    @Test void actualClientCleanupUsesAlreadyRecordedNoticeContract()throws Exception{
        String client=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        assertTrue(client.contains("if(playback.error()!=null){leave(playback.errorForLeaveNotice());return;}"));
        assertFalse(client.contains("leave(playback.error())"));
        assertTrue(client.contains("playback!=null&&playback.error()!=null?playback.errorForLeaveNotice():message.reason()"));
    }
}
