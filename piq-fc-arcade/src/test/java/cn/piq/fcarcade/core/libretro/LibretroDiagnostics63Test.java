package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.client.ui.DeviceNotices;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibretroDiagnostics63Test {
    @Test void childOutputIsBoundedAndKeepsItsNewestBytes() {
        var output = new LibretroNesCore.WorkerOutput();
        byte[] first = "old".repeat(5000).getBytes(StandardCharsets.UTF_8);
        output.append(first, first.length);
        byte[] last = ("x".repeat(9000) + "\nConnection refused").getBytes(StandardCharsets.UTF_8);
        output.append(last, last.length);
        String retained = output.text(null);
        assertEquals(8192, retained.length());
        assertTrue(retained.endsWith("\nConnection refused"));
        assertFalse(retained.contains("old"));
    }

    @Test void workerTokenAndTerminalControlsAreNotExposed() {
        var output = new LibretroNesCore.WorkerOutput();
        String token = "12345678-abcd-abcd-abcd-123456789abc";
        byte[] bytes = ("failed " + token + "\u0000\r\n\t原因").getBytes(StandardCharsets.UTF_8);
        // Also exercise a multi-byte UTF-8 character split across reads.
        output.append(java.util.Arrays.copyOf(bytes, bytes.length - 1), bytes.length - 1);
        output.append(new byte[]{bytes[bytes.length - 1]}, 1);
        assertEquals("failed [redacted]\n\t原因", output.text(token));
    }

    @Test void startupFailureHasASpecificPlayerSummaryWithoutTechnicalDetails() {
        String expected = "FC 核心运行失败，请复制诊断";
        assertEquals(expected, DeviceNotices.summarize("FC native worker exited during startup (1)"));
        assertEquals(expected, DeviceNotices.summarize("FC libretro failed: EOFException\nFC worker output:\nConnection refused"));
    }
}
