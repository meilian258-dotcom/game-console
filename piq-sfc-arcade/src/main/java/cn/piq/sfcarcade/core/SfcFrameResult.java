// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

/** Metadata returned after running exactly one emulated video frame. */
public record SfcFrameResult(SfcVideoMode videoMode, int stereoSampleFrames, long emulatedFrameNumber) {
    public static final int AUDIO_CHANNELS = 2;
    public static final int AUDIO_SAMPLE_RATE = 48_000;

    public SfcFrameResult {
        if (videoMode == null) {
            throw new NullPointerException("videoMode");
        }
        if (stereoSampleFrames < 0) {
            throw new IllegalArgumentException("Negative audio frame count");
        }
        if (emulatedFrameNumber < 0) {
            throw new IllegalArgumentException("Negative emulated frame number");
        }
    }

    public int requiredPcmShorts() {
        return Math.multiplyExact(stereoSampleFrames, AUDIO_CHANNELS);
    }
}

