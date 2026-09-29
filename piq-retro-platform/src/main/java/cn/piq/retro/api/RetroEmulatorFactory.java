// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.api;

import java.nio.file.Path;

/** Client-side factory contract; invoke off the game thread after host authorization. */
@FunctionalInterface
public interface RetroEmulatorFactory {
    /** The provider validates its file format and bounds; the host owns cancellation and cleanup. */
    RetroEmulator open(Path rom) throws Exception;
}
