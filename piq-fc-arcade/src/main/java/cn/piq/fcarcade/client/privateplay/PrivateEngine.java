// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.privateplay;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import java.util.concurrent.CompletableFuture;

/** Client-only game data boundary; implementations never publish ROM, input, media or saves. */
public interface PrivateEngine extends CabinetEmulator {
    void paused(boolean value);
    /** Freeze/copy/save/close on the owner worker, never wait on the Minecraft thread. */
    CompletableFuture<SaveResult> stopAndSave();
    record SaveResult(boolean saved, String message) {}
}
