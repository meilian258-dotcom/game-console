// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

/**
 * Allocation-conscious contract implemented by the future jgenesis adapter.
 * A core instance is owned by one emulation thread and is not thread-safe.
 */
public interface SfcCore extends AutoCloseable {
    String backendName();

    void loadRom(SfcRomImage rom);

    SfcFrameResult runFrame(SfcControllerState playerOne, SfcControllerState playerTwo);

    /** Copies the most recently completed RGBA8888 frame into {@code destination}. */
    void copyRgbaFrame(byte[] destination);

    /** Copies the most recent interleaved stereo PCM16 samples and returns copied stereo frames. */
    int copyAudioPcm16(short[] destination);

    byte[] saveState();

    void loadState(byte[] state);

    byte[] saveSram();

    void loadSram(byte[] sram);

    void reset(boolean hard);

    @Override
    void close();
}

