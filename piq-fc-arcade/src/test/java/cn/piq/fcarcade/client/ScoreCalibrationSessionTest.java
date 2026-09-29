package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreCalibrationSessionTest {
    @Test
    void findsBcdAndLittleEndianCandidatesAcrossSamples() {
        ScoreCalibrationSession.Analysis analysis =
                ScoreCalibrationSession.analyze(List.of(
                        new ScoreCalibrationSession.Sample(12, ram(0x12, 12)),
                        new ScoreCalibrationSession.Sample(34, ram(0x34, 34)),
                        new ScoreCalibrationSession.Sample(56, ram(0x56, 56))));

        assertTrue(analysis.candidates().contains(
                "BCD8 divisor=1 address=$0042 length=1"));
        assertTrue(analysis.candidates().contains(
                "LE16 divisor=1 address=$0100 length=2"));
    }

    @Test
    void findsRoadRacingThreeByteLittleEndianPackedBcdMirrors() {
        ScoreCalibrationSession.Analysis analysis =
                ScoreCalibrationSession.analyze(List.of(
                        new ScoreCalibrationSession.Sample(
                                1_500, roadRacingRam(0x00, 0x15, 0x00)),
                        new ScoreCalibrationSession.Sample(
                                6_750, roadRacingRam(0x50, 0x67, 0x00)),
                        new ScoreCalibrationSession.Sample(
                                16_290, roadRacingRam(0x90, 0x62, 0x01))));

        assertTrue(analysis.candidates().contains(
                "BCD24_LE divisor=1 address=$0057 length=3"));
        assertTrue(analysis.candidates().contains(
                "BCD24_LE divisor=1 address=$005D length=3"));
    }

    private static byte[] ram(int bcdScore, int binaryScore) {
        byte[] ram = new byte[NesCore.CPU_RAM_BYTES];
        ram[0x42] = (byte) bcdScore;
        ram[0x100] = (byte) binaryScore;
        ram[0x101] = (byte) (binaryScore >>> 8);
        return ram;
    }

    private static byte[] roadRacingRam(
            int lowDigits,
            int middleDigits,
            int highDigits
    ) {
        byte[] ram = new byte[NesCore.CPU_RAM_BYTES];
        ram[0x57] = (byte) lowDigits;
        ram[0x58] = (byte) middleDigits;
        ram[0x59] = (byte) highDigits;
        ram[0x5D] = (byte) lowDigits;
        ram[0x5E] = (byte) middleDigits;
        ram[0x5F] = (byte) highDigits;
        return ram;
    }
}
