// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.client.ui.DeviceNotices;
import cn.piq.retro.api.RetroEmulator;

/** Read the admitted local emulator before teardown; no core calls or network messages. */
final class CabinetFailureNotices {
    private CabinetFailureNotices() {}

    static boolean capture(RetroEmulator emulator, String reason) {
        String detail;
        try {
            detail = emulator.failureDiagnostic();
        } catch (RuntimeException | LinkageError unavailable) {
            detail = "本机核心失败诊断暂不可用";
        }
        if (detail == null || detail.isBlank()) return false; // Keep legacy notices unchanged.
        DeviceNotices.recordLocalDiagnostic("街机", reason, detail);
        return true;
    }
}
