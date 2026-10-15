package cn.piq.nativearcade.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMameLoadFailureTest {
    private static final String FATAL = "Fatal error: Required files are missing, the machine cannot be run.\n";
    @Test void fatalAuditShowsDeviceDataFirstAndExplainsWholeContentUpload() {
        var report = NativeMameLoadFailure.interpret("dino.zip", "buf1 NOT FOUND (tried in dino)\n"
                + "ioa1 NOT FOUND (tried in dino)\n"
                + "dl-1425.bin NOT FOUND (tried in qsound_hle qsound dino)\n"
                + "dl-1425.bin NOT FOUND (tried in qsound_hle qsound dino)\n" + FATAL);
        assertNotNull(report);
        assertTrue(report.summary().startsWith("MAME 游戏数据缺失：dl-1425.bin"));
        assertFalse(report.summary().contains("Netplay"));
        assertTrue(report.detail().contains("qsound_hle.zip、qsound.zip"));
        assertTrue(report.detail().contains("重新选择并上传完整内容"));
        assertTrue(report.detail().contains("不代表游戏数据完整"));
        assertEquals(1, report.detail().split("dl-1425.bin", -1).length - 1);
    }
    @Test void genericExitAndNonfatalAuditNeverClaimMissingBios() {
        for (String log : new String[]{"RetroArch 原生端启动退出：1", "core.dll loaded\nfoo NOT FOUND (tried in dino)",
                "foo WRONG CHECKSUMS\nMAME running", "", "Fatal error: unknown driver"})
            assertNull(NativeMameLoadFailure.interpret("dino.zip", log));
        assertNull(NativeMameLoadFailure.interpret("dino.zip", null));
    }
    @Test void noGameSpecificGuessOrUnsafePathsInHumanSummary() {
        var report = NativeMameLoadFailure.interpret("../secret.zip", "C:/private/name NOT FOUND\n"
                + "../../private NOT FOUND\nsecret:token NOT FOUND\nvalid.bin NOT FOUND (tried in arbitrary)\n" + FATAL);
        assertTrue(report.summary().contains("valid.bin"));
        assertFalse(report.detail().contains("private"));
        assertFalse(report.detail().contains("secret"));
        assertFalse(report.detail().contains("arbitrary.zip"));
        assertFalse(report.detail().contains("qsound"));
        assertTrue(report.detail().contains("游戏：所选游戏"));
    }
    @Test void fileListAndInputAreBounded() {
        StringBuilder log = new StringBuilder();
        for (int i=0;i<100;i++) log.append("rom").append(i).append(".bin NOT FOUND\n");
        var report = NativeMameLoadFailure.interpret("sample.zip", log + FATAL);
        assertTrue(report.detail().contains("列表已截断"));
        assertTrue(report.detail().contains("rom31.bin"));
        assertFalse(report.detail().contains("rom32.bin"));
        assertTrue(report.detail().length() < 4096);
        assertNull(NativeMameLoadFailure.interpret("sample.zip", FATAL + "x".repeat(40000)));
    }
    @Test void missingNamesWithoutRecognizableLinesStillGiveBoundedHonestReason() {
        var report = NativeMameLoadFailure.interpret("sample.zip", FATAL);
        assertNotNull(report);
        assertTrue(report.detail().contains("请查看下方原生记录"));
        assertFalse(report.detail().contains("qsound"));
    }
}
