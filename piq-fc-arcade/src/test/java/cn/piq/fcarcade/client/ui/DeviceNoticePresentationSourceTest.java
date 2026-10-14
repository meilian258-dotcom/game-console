package cn.piq.fcarcade.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source boundary checks complement chat policy tests; they do not simulate a graphical client. */
class DeviceNoticePresentationSourceTest {
    @Test void errorsUseLocalChatNotTopRightToastOrServerChat() throws Exception {
        var source = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ui/DeviceNoticesClient.java"));
        assertFalse(source.contains("SystemToast"));
        assertFalse(source.contains("getToasts()"));
        assertTrue(source.contains("mc.gui.getChat().addMessage("));
        assertFalse(source.contains("sendChat("));
        assertTrue(source.contains("DeviceNotices.snapshot()"));
    }
    @Test void runtimePageRemainsManualAndDoesNotScanFilesOnClientTick() throws Exception {
        var source = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/runtime/RuntimeEnvironmentClient.java"));
        assertTrue(source.contains("IConfigScreenFactory"));
        assertTrue(source.contains("new RuntimeEnvironmentScreen(parent)"));
        assertFalse(source.contains("setScreen("));
        assertFalse(source.contains("Files."));
        assertTrue(source.contains("RuntimeStartupState.failure("));
    }
}
