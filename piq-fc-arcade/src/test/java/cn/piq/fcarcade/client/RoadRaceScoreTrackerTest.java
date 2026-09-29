package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRaceScoreTrackerTest {
    @Test
    void ignoresExistingRecordAndTracksEachRoundAfterZero() {
        RoadRaceScoreTracker tracker = new RoadRaceScoreTracker();
        StubCore core = new StubCore();

        core.setScore(16_290);
        assertTrue(sample(tracker, core).isEmpty());

        core.setScore(0);
        assertTrue(sample(tracker, core).isEmpty());
        assertFalse(tracker.consumeRoundEnded());

        core.setScore(200);
        assertEquals(200, sample(tracker, core).orElseThrow());
        core.setScore(1_350);
        assertEquals(1_350, sample(tracker, core).orElseThrow());

        core.setScore(0);
        assertTrue(sample(tracker, core).isEmpty());
        assertTrue(tracker.consumeRoundEnded());
        assertFalse(tracker.consumeRoundEnded());
        core.setScore(500);
        assertEquals(500, sample(tracker, core).orElseThrow());
    }

    private static OptionalInt sample(RoadRaceScoreTracker tracker, StubCore core) {
        OptionalInt result = OptionalInt.empty();
        for (int index = 0; index < 7; index++) {
            OptionalInt observed = tracker.observe(core);
            if (observed.isPresent()) result = observed;
        }
        return result;
    }

    private static final class StubCore implements NesCore {
        private byte[] ram = new byte[CPU_RAM_BYTES];

        private void setScore(int score) {
            int remaining = score;
            for (int index = 0; index < 3; index++) {
                int pair = remaining % 100;
                int packed = ((pair / 10) << 4) | (pair % 10);
                ram[0x57 + index] = (byte) packed;
                ram[0x5D + index] = (byte) packed;
                remaining /= 100;
            }
        }

        @Override public void loadRom(byte[] rom) { }
        @Override public void reset() { }
        @Override public void setControllerState(int player, int buttonMask) { }
        @Override public void runFrame() { }
        @Override public void copyFrameRgba(byte[] destination) { }
        @Override public int copyAudioSamples(float[] destination) { return 0; }
        @Override public void copyCpuRam(byte[] destination) {
            System.arraycopy(ram, 0, destination, 0, ram.length);
        }
        @Override public byte[] saveTransientState() { return new byte[0]; }
        @Override public void loadTransientState(byte[] state) { }
        @Override public void close() { }
    }
}
