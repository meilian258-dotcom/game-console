package cn.piq.fcarcade.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpatialAudioTest {
    @Test
    void keepsFullVolumeNearbyAndFadesLinearlyToSilence() {
        assertEquals(1.0F, SpatialAudio.distanceGain(0, 2, 16));
        assertEquals(1.0F, SpatialAudio.distanceGain(2, 2, 16));
        assertEquals(0.5F, SpatialAudio.distanceGain(9, 2, 16), 0.0001F);
        assertEquals(0.0F, SpatialAudio.distanceGain(16, 2, 16));
        assertEquals(0.0F, SpatialAudio.distanceGain(30, 2, 16));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SpatialAudio.distanceGain(Double.NaN, 2, 16));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpatialAudio.distanceGain(3, 16, 16));
    }
}
