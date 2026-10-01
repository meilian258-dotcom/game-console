// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.util.List;
import java.util.Set;

/**
 * Version 1 owner-thread core boundary. Preparation, every operation and close may block:
 * never call on a Minecraft game/render thread. This is NOT an authorization, save-owner,
 * scheduling or Netplay API. Existing transport value records remain ABI-compatible.
 * Output PCM is at info.sampleRate(); presenters must convert to RetroFrame's 48 kHz.
 */
public interface LibretroRuntime extends AutoCloseable {
    int API_VERSION = 1;
    enum Capability { SOFTWARE_VIDEO, OPENGL_COMPAT_VIDEO, DIGITAL_PADS, LIGHT_GUN,
        POINTER, MOUSE, KEYBOARD, STATE, SAVE_MEMORY }
    default Set<Capability> capabilities() {
        return Set.of(Capability.SOFTWARE_VIDEO, Capability.DIGITAL_PADS, Capability.STATE, Capability.SAVE_MEMORY);
    }
    default LibretroRuntimes.Backend backend() { return LibretroRuntimes.Backend.PROCESS; }
    default int rotation() { return 0; }
    /** Nonblocking, thread-safe watchdog status; does not enter the native library. */
    default String diagnosticError() { return ""; }
    LibretroProcess.Info load(byte[] content);
    /** Named content is optional and must be explicitly implemented by the runtime. */
    default LibretroProcess.Info loadBundle(String mainName,java.util.Map<String,byte[]> files) {
        throw new UnsupportedOperationException("Runtime has no named-content adapter");
    }
    LibretroProcess.Info info();
    String coreVersion();
    LibretroProcess.Output run(List<LibretroProcess.Controls> frames, int outputMask);
    LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> frames, int outputMask, int memoryId);
    LibretroProcess.Info reset();
    byte[] serialize();
    void restore(byte[] state);
    byte[] memory(int id);
    LibretroSaveMemory saveMemory();
    void restoreSaveMemory(LibretroSaveMemory memory);
    byte[] persistenceIdentity();
    @Override void close();
}
