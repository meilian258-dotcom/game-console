// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.client.ui.DeviceNotices;
import cn.piq.retro.api.RetroEmulator;
import cn.piq.retro.api.RetroFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetFailureNoticesTest {
    private static class Legacy implements RetroEmulator {
        boolean closed;
        public boolean isReady() { throw new AssertionError("Polled readiness"); }
        public String error() { return "RetroArch 原生端启动退出：1"; }
        public void offerInput(int p1, int p2) { throw new AssertionError("Changed input"); }
        public void clearInput() { throw new AssertionError("Changed input"); }
        public RetroFrame pollFrame() { throw new AssertionError("Consumed output"); }
        public void close() { closed = true; }
    }

    @AfterEach void clear() { DeviceNotices.clear(); }

    @Test void legacyDefaultKeepsTheExistingNoticePath() {
        var emulator = new Legacy();
        assertEquals("", emulator.failureDiagnostic());
        assertFalse(CabinetFailureNotices.capture(emulator, emulator.error()));
        assertNull(DeviceNotices.last());
        assertFalse(emulator.closed);
    }

    @Test void failureIsRetainedBeforeTheAdapterCanDiscardItsResources() {
        var emulator = new Legacy() {
            @Override public String failureDiagnostic() { return closed ? "" : "local output with final rejection"; }
        };
        assertTrue(CabinetFailureNotices.capture(emulator, emulator.error()));
        assertFalse(emulator.closed);
        emulator.close();
        var notice = DeviceNotices.last();
        assertEquals("RetroArch 核心已退出，请复制诊断", notice.summary());
        assertTrue(notice.detail().contains("local output with final rejection"));
        assertEquals("", emulator.failureDiagnostic());
    }

    @Test void brokenOptionalDiagnosticCannotPreventFailureTeardown() {
        var emulator = new Legacy() {
            @Override public String failureDiagnostic() { throw new AbstractMethodError("old addon"); }
        };
        assertTrue(CabinetFailureNotices.capture(emulator, emulator.error()));
        emulator.close();
        assertTrue(emulator.closed);
        assertTrue(DeviceNotices.last().detail().contains("诊断暂不可用"));
        assertFalse(DeviceNotices.last().detail().contains("old addon"));
    }
}
