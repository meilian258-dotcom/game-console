package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.score.RoadRaceScoreRule;

import java.util.OptionalInt;

final class RoadRaceScoreTracker {
    private static final int SAMPLE_INTERVAL_FRAMES = 6;

    private final byte[] cpuRam = new byte[NesCore.CPU_RAM_BYTES];
    private int framesUntilSample;
    private boolean armed;
    private int bestScore;
    private boolean roundEnded;

    OptionalInt observe(NesCore core) {
        if (framesUntilSample > 0) {
            framesUntilSample--;
            return OptionalInt.empty();
        }
        framesUntilSample = SAMPLE_INTERVAL_FRAMES - 1;
        core.copyCpuRam(cpuRam);
        int score = RoadRaceScoreRule.decode(cpuRam);
        if (score < 0) return OptionalInt.empty();

        if (!armed) {
            if (score == 0) armed = true;
            return OptionalInt.empty();
        }
        if (score == 0) {
            if (bestScore > 0) roundEnded = true;
            bestScore = 0;
            return OptionalInt.empty();
        }
        if (score <= bestScore) return OptionalInt.empty();
        bestScore = score;
        return OptionalInt.of(score);
    }

    boolean consumeRoundEnded() {
        boolean result = roundEnded;
        roundEnded = false;
        return result;
    }
}
