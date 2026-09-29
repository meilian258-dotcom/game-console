package cn.piq.fcarcade.server;

import java.util.Optional;
import java.util.UUID;

/**
 * Tracks one cabinet's current Road Race scoring run independently from the
 * persisted personal-best table.
 */
final class RoadRaceRoundState {
    private int lastObservedScore;
    private UUID recordPlayerId;
    private String recordPlayerName;
    private int previousPersonal;
    private int previousGlobal;
    private int finalScore;

    boolean startsNewRound(int score) {
        return score > 0 && lastObservedScore > 0 && score < lastObservedScore;
    }

    void observe(int score) {
        lastObservedScore = score;
        if (recordPlayerId != null && score > finalScore) {
            finalScore = score;
        }
    }

    Optional<Breakthrough> recordImprovement(
            UUID playerId,
            String playerName,
            int oldPersonal,
            int oldGlobal,
            int score
    ) {
        if (recordPlayerId != null) {
            if (score > finalScore) finalScore = score;
            return Optional.empty();
        }
        recordPlayerId = playerId;
        recordPlayerName = playerName;
        previousPersonal = oldPersonal;
        previousGlobal = oldGlobal;
        finalScore = score;
        return Optional.of(new Breakthrough(
                playerId,
                playerName,
                oldPersonal,
                oldGlobal,
                score));
    }

    Optional<Settlement> finish() {
        Optional<Settlement> result = recordPlayerId == null
                ? Optional.empty()
                : Optional.of(new Settlement(
                        recordPlayerId,
                        recordPlayerName,
                        previousPersonal,
                        previousGlobal,
                        finalScore));
        reset();
        return result;
    }

    private void reset() {
        lastObservedScore = 0;
        recordPlayerId = null;
        recordPlayerName = null;
        previousPersonal = 0;
        previousGlobal = 0;
        finalScore = 0;
    }

    record Breakthrough(
            UUID playerId,
            String playerName,
            int previousPersonal,
            int previousGlobal,
            int score
    ) {
        boolean globalBest() {
            return score > previousGlobal;
        }
    }

    record Settlement(
            UUID playerId,
            String playerName,
            int previousPersonal,
            int previousGlobal,
            int finalScore
    ) {
    }
}
