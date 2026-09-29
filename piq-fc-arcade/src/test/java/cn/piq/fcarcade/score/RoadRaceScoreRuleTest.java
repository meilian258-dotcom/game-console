package cn.piq.fcarcade.score;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadRaceScoreRuleTest {
    @Test
    void decodesThreeBytePackedBcdMirrors() {
        assertEquals(200, RoadRaceScoreRule.decode(ramFor(200)));
        assertEquals(1_350, RoadRaceScoreRule.decode(ramFor(1_350)));
        assertEquals(6_750, RoadRaceScoreRule.decode(ramFor(6_750)));
        assertEquals(16_290, RoadRaceScoreRule.decode(ramFor(16_290)));
        assertEquals(999_999, RoadRaceScoreRule.decode(ramFor(999_999)));
    }

    @Test
    void rejectsInvalidDigitsAndDifferentMirrors() {
        byte[] invalidDigit = ramFor(200);
        invalidDigit[0x57] = (byte) 0xFA;
        invalidDigit[0x5D] = (byte) 0xFA;
        assertEquals(-1, RoadRaceScoreRule.decode(invalidDigit));

        byte[] mismatch = ramFor(16_290);
        mismatch[0x5D] = 0;
        assertEquals(-1, RoadRaceScoreRule.decode(mismatch));
    }

    static byte[] ramFor(int score) {
        byte[] ram = new byte[NesCore.CPU_RAM_BYTES];
        int remaining = score;
        for (int index = 0; index < 3; index++) {
            int pair = remaining % 100;
            int packed = ((pair / 10) << 4) | (pair % 10);
            ram[0x57 + index] = (byte) packed;
            ram[0x5D + index] = (byte) packed;
            remaining /= 100;
        }
        return ram;
    }
}
