// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.watch;

import java.util.function.BooleanSupplier;

/** Final start-only guard; neither revokes running sessions nor changes a selected backend. */
final class WatchStartConsent {
    private WatchStartConsent() {}

    static void requireAllowed(boolean jni, BooleanSupplier currentConsent) {
        // Evaluate on the client thread after content preparation, not a snapshot taken before IO.
        // Process Netplay must not depend on the separate JNI preference or platform check.
        if (jni && !currentConsent.getAsBoolean()) {
            throw new IllegalStateException("本机已停用 JNI Netplay 或平台不支持；本次旁观未启动，可用 /gameconsole-jni-netplay 恢复");
        }
    }
}
