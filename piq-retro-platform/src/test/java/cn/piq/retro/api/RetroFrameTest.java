package cn.piq.retro.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetroFrameTest {
    @Test void preservesTransferredArraysChannelsAndRawPresentation() {
        int[] pixels = {0x80402010, 0xff0000ff};
        short[] audio = {Short.MIN_VALUE, Short.MAX_VALUE};
        for (int turn = 0; turn < 4; turn++) {
            var frame = new RetroFrame(2, 1, pixels, 4F / 3F, turn, audio);
            assertSame(pixels, frame.abgr());
            assertSame(audio, frame.pcm48k());
            assertEquals(4F / 3F, frame.displayAspect());
            assertEquals(turn, frame.rotation());
            assertEquals(0x10, frame.abgr()[0] & 255); // red in RGBA byte order
            assertEquals(0x20, (frame.abgr()[0] >>> 8) & 255);
            assertEquals(0x40, (frame.abgr()[0] >>> 16) & 255);
            assertEquals(0x80, (frame.abgr()[0] >>> 24) & 255);
            assertEquals(Short.MIN_VALUE, frame.pcm48k()[0]);
            assertEquals(Short.MAX_VALUE, frame.pcm48k()[1]);
        }
    }

    @Test void acceptsExactLegacyLimitsWithoutClamping() {
        assertDoesNotThrow(() -> new RetroFrame(2048, 2048, new int[2048 * 2048], .1F, 3, new short[32768]));
        assertDoesNotThrow(() -> new RetroFrame(1, 1, new int[1], 10F, 0, new short[0]));
    }

    @Test void rejectsDimensionsAndIncorrectPixelLength() {
        for (int width : new int[]{Integer.MIN_VALUE, -1, 0, 2049, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new RetroFrame(width, 1, new int[1], 1F, 0, new short[0]));
        for (int height : new int[]{Integer.MIN_VALUE, -1, 0, 2049, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, height, new int[1], 1F, 0, new short[0]));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, null, 1F, 0, new short[0]));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[0], 1F, 0, new short[0]));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[2], 1F, 0, new short[0]));
    }

    @Test void rejectsInvalidAspectRotationAndAudio() {
        for (float aspect : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, -.1F, Math.nextDown(.1F), Math.nextUp(10F)})
            assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[1], aspect, 0, new short[0]));
        for (int turn : new int[]{Integer.MIN_VALUE, -1, 4, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[1], 1F, turn, new short[0]));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[1], 1F, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[1], 1F, 0, new short[1]));
        assertThrows(IllegalArgumentException.class, () -> new RetroFrame(1, 1, new int[1], 1F, 0, new short[32770]));
    }
}
