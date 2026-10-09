package cn.piq.fcarcade.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

class DeviceNoticesTest {
    @Test void diskFullIsNotMisreportedAsBrokenRuntimeOrRom() {
        for (String text : new String[]{"Netplay 启动失败：磁盘空间不足。 core.dll rom.zip", "Generic libretro failed: No space left on device"})
            assertEquals("磁盘空间不足，请清理运行目录所在磁盘", DeviceNotices.summarize(text));
    }
    @AfterEach void clean() { DeviceNotices.clear(); }
    @Test void exactRoutineOnly() {
        assertTrue(DeviceNoticePolicy.routineKey("message.piq_fc_arcade.controller_returned"));
        assertFalse(DeviceNoticePolicy.routineKey("message.other_mod.controller_returned"));
        assertFalse(DeviceNoticePolicy.routineKey("message.piq_fc_arcade.controller_p2_approval"));
        assertFalse(DeviceNoticePolicy.routineKey("message.piq_fc_arcade.new_unknown_notice"));
        assertTrue(DeviceNoticePolicy.routineText("[SFC] 已借出 P2 手柄；不会自动开机，请按主机电源"));
        assertTrue(DeviceNoticePolicy.routineText("已取下 P1 手柄；按电源才会开机。"));
        assertTrue(DeviceNoticePolicy.routineText("已加入街机 P4；再次右键退出"));
        assertFalse(DeviceNoticePolicy.routineText("已加入街机 P4；再次右键退出；但存档失败"));
        assertFalse(DeviceNoticePolicy.routineText("[SFC] 输入序列或频率异常，手柄已归还；主机继续"));
        assertFalse(DeviceNoticePolicy.routineText("[SFC] 申请已发给运行宿主；批准后领取手柄并同步当前进度"));
        assertFalse(DeviceNoticePolicy.routineText("正在上传缺失游戏文件：10% · 右键本机可取消"));
        assertFalse(DeviceNoticePolicy.routineText("来自其他模组的新提示"));
        assertTrue(DeviceNoticePolicy.routineKey("message.piq_fc_arcade.home_wire_selected"));
        assertTrue(DeviceNoticePolicy.routineKey("message.piq_fc_arcade.furniture.folded"));
        assertTrue(DeviceNoticePolicy.routineText("已连接 4 席：副柜从 P3 开始；两台机柜外观各自保留。"));
        assertFalse(DeviceNoticePolicy.routineText("已连接 4 席：副柜从 P3 开始；两台机柜外观各自保留。 但校验失败"));
        assertTrue(DeviceNoticePolicy.errorText("[SFC] 输入序列或频率异常，手柄已归还；主机继续"));
        assertTrue(DeviceNoticePolicy.errorText("机柜不可用，或没有操作权限。"));
        assertFalse(DeviceNoticePolicy.errorText("[SFC] 未知的新服务端信息"));
        assertTrue(DeviceNoticePolicy.routineText("请到主机或光枪支架归还光枪。"));
        assertTrue(DeviceNoticePolicy.routineText("光枪已领取或等待主机批准；批准后可射击，并在 P1 空闲时操作游戏按键。"));
        assertFalse(DeviceNoticePolicy.routineText("已向主机玩家申请 光枪；再次点击取消。"));
        assertFalse(DeviceNoticePolicy.routineText("运行宿主拒绝了加入申请"));
    }
    @Test void onlyPowerAndResetHover() {
        assertEquals("右键 · 开机",DeviceNoticePolicy.buttonLabel("POWER",false));
        assertEquals("右键 · 关机",DeviceNoticePolicy.buttonLabel("POWER",true));
        assertEquals("右键 · 重置",DeviceNoticePolicy.buttonLabel("RESET",true));
        assertEquals("右键 · 重置",DeviceNoticePolicy.buttonLabel("RESET",false));
        for (String control : new String[]{"NONE","CONTROLLER_ONE","CONTROLLER_TWO","VOLUME_UP","VOLUME_DOWN","UNKNOWN"})
            for (boolean powered : new boolean[]{true,false}) assertNull(DeviceNoticePolicy.buttonLabel(control,powered));
    }
    @Test void netplayFailureTakesPriorityOverIncidentalCoreAndRomLogs(){
        var e=DeviceNotices.record("街机 Netplay","Loaded core.dll and rom.zip; all checks passed",new IllegalStateException("Netplay 连接已结束，请重新加入"));
        assertEquals("本局联机连接已结束",e.summary());
        assertTrue(e.detail().contains("core.dll"));
        assertEquals("Netplay 核心已退出，请复制诊断",DeviceNotices.summarize("Netplay 原生端启动退出：1"));
        assertEquals("Netplay 连接或同步超时，请重新加入",DeviceNotices.summarize("等待主持端 Netplay 连接超时"));
        assertEquals("联机数据积压或顺序异常，请重新加入",DeviceNotices.summarize("Netplay 接收积压或数据顺序异常，请重新加入"));
        assertEquals("Netplay 组件不匹配，请核对配套版本",DeviceNotices.summarize("Netplay 帧协议不匹配"));
    }
    @Test void errorsAreHumanReadableWithoutMisidentifyingDllAsBios() {
        assertTrue(DeviceNotices.summarize("NoSuchFileException: E:/private/neogeo.zip").contains("BIOS"));
        assertTrue(DeviceNotices.summarize("Missing piqneogeo_libretro.dll").contains("运行环境"));
        assertTrue(DeviceNotices.summarize("缺少 piqneogeo_libretro.dll").contains("运行环境"));
        assertFalse(DeviceNotices.summarize("Missing piqneogeo_libretro.dll").contains("BIOS"));
        assertTrue(DeviceNotices.summarize("AccessDeniedException: C:/private/rom.zip").contains("权限"));
        assertTrue(DeviceNotices.summarize("同步失败：java.util.concurrent.TimeoutException").contains("同步超时"));
        assertFalse(DeviceNotices.summarize("E:/private/long/file/path/libretro.dll").contains("E:/"));
    }
    @Test void boundedNewestFirstMemoryRetainsOriginalExceptionAndDoesNotMutateSnapshot() {
        DeviceNotices.clear();
        var first = DeviceNotices.record("FC","failed to open",new IllegalStateException("specific root cause"));
        assertTrue(first.detail().contains("IllegalStateException: specific root cause"));
        assertTrue(first.detail().contains("DeviceNoticesTest"));
        assertEquals(first,DeviceNotices.last());
        var snapshot=DeviceNotices.snapshot();
        assertThrows(UnsupportedOperationException.class,()->snapshot.clear());
        DeviceNotices.record("FC","same error"); var duplicate=DeviceNotices.record("FC","same error");
        assertEquals(2,DeviceNotices.snapshot().size()); assertEquals(duplicate,DeviceNotices.last());
        for(int i=0;i<40;i++)DeviceNotices.record("GBA","failure "+i);
        assertEquals(32,DeviceNotices.snapshot().size());
        assertEquals("failure 39",DeviceNotices.last().detail());
        assertEquals(1,snapshot.size());
        assertTrue(DeviceNotices.last().id()>first.id());
        DeviceNotices.clear();assertNull(DeviceNotices.last());
    }
    @Test void failureDetailIsBoundedButExplicitAboutFullLog() {
        var entry=DeviceNotices.record("SFC","x".repeat(17_000));
        assertTrue(entry.detail().length()<16_500);
        assertTrue(entry.detail().endsWith("完整记录见客户端日志]"));
        assertTrue(DeviceNoticePolicy.errorKey("message.piq_fc_arcade.run_failed"));
        assertFalse(DeviceNoticePolicy.errorKey("message.other_mod.run_failed"));
        assertFalse(DeviceNoticePolicy.errorKey("message.piq_fc_arcade.join_request_denied"));
        assertFalse(DeviceNoticePolicy.errorKey("message.piq_fc_arcade.unknown_future_error_failed"));
    }
    @Test void retroArchLocalFailuresDoNotBecomeNetplayReconnectAdvice() {
        assertEquals("RetroArch 核心已退出，请复制诊断", DeviceNotices.summarize("RetroArch 原生端启动退出：1"));
        assertEquals("RetroArch 核心已退出，请复制诊断", DeviceNotices.summarize("RetroArch 核心输出已结束，请查看诊断"));
        assertEquals("RetroArch 本机运行超时，请查看诊断", DeviceNotices.summarize("RetroArch 音画桥启动超时"));
        assertEquals("RetroArch 组件不匹配，请核对配套版本", DeviceNotices.summarize("RetroArch 帧协议不匹配"));
        assertEquals("RetroArch 需要 Windows x64 客户端", DeviceNotices.summarize("RetroArch 本机运行仅支持 Windows x64 客户端"));
    }
    @Test void nativeDetailStaysInLocalMemoryAndOnlySafeSummaryIsLogged() {
        var messages = new java.util.ArrayList<String>();
        var logger = java.util.logging.Logger.getLogger("PIQ-DeviceNotices");
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { messages.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            String privateDetail = "Loaded C:/private/example/core.dll; final native output";
            var entry = DeviceNotices.recordLocalDiagnostic("街机", "RetroArch 原生端启动退出：1", privateDetail);
            assertEquals("RetroArch 核心已退出，请复制诊断", entry.summary());
            assertTrue(entry.detail().contains(privateDetail));
            assertEquals(entry, DeviceNotices.recordLocalDiagnostic("街机", "RetroArch 原生端启动退出：1", privateDetail));
            assertEquals(1, DeviceNotices.snapshot().size());
            assertTrue(messages.stream().anyMatch(message -> message.contains(entry.summary())));
            assertTrue(messages.stream().noneMatch(message -> message.contains("C:/private") || message.contains("final native output")));
        } finally { logger.removeHandler(handler); }
    }
    @Test void classifiedMissingGameDataIsNotOverriddenByIncidentalNativeLogs() {
        String reason = "MAME 游戏数据缺失：example.bin、chip1 等；请查看诊断";
        String summary = "游戏数据不完整，请复制诊断查看缺失文件";
        assertEquals(summary, DeviceNotices.summarize(reason));
        assertEquals(summary, DeviceNotices.summarize("其他核心 游戏数据缺失：example.bin"));
        var notice = DeviceNotices.recordLocalDiagnostic("街机", reason,
                "Loaded C:/private/example/core.dll; Netplay 连接已结束; unrelated startup text");
        assertEquals(summary, notice.summary());
        assertTrue(notice.detail().contains("core.dll"));
        assertTrue(notice.detail().contains("Netplay"));
        assertEquals(1, DeviceNotices.snapshot().size());
    }
    @Test void localDiagnosticRetentionIsBoundedWithoutClaimingAFullDiskLogExists() {
        var entry = DeviceNotices.recordLocalDiagnostic("街机", "RetroArch 核心输出已结束", "x".repeat(17_000));
        assertTrue(entry.detail().length() < 16_500);
        assertTrue(entry.detail().endsWith("原生输出未写入客户端日志]"));
        assertFalse(entry.detail().contains("完整记录见客户端日志"));
        var snapshot = DeviceNotices.snapshot();
        for (int i = 0; i < 40; i++) DeviceNotices.recordLocalDiagnostic("街机", "RetroArch 原生端启动退出：1", "failure " + i);
        assertEquals(32, DeviceNotices.snapshot().size());
        assertEquals(1, snapshot.size());
        assertTrue(DeviceNotices.last().detail().endsWith("failure 39"));
    }
}
