package cn.piq.fcarcade.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NesPcmEncoderTest {
    @Test
    void encodesSignedLittleEndianPcmAndHonorsMute() {
        NesPcmEncoder encoder = new NesPcmEncoder();
        byte[] audible = encoder.encode(new float[]{0.5F, 0.0F}, 2, 1.0F);
        short first = ByteBuffer.wrap(audible)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort();
        assertTrue(first > 0);

        encoder.reset();
        byte[] muted = encoder.encode(new float[]{0.5F, 0.0F}, 2, 0.0F);
        assertEquals(0, ByteBuffer.wrap(muted).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    @Test
    void highPassFilterRemovesSteadyDcOffset() {
        NesPcmEncoder encoder = new NesPcmEncoder();
        float[] constant = new float[4096];
        java.util.Arrays.fill(constant, 0.5F);
        byte[] pcm = encoder.encode(constant, constant.length, 1.0F);
        short last = ByteBuffer.wrap(pcm, pcm.length - 2, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort();
        assertTrue(Math.abs(last) < 8, "稳定直流应衰减到接近静音");
    }
}
