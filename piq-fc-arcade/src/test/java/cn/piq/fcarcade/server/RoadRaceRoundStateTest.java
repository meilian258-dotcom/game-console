package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRaceRoundStateTest {
    @Test
    void announcesOnlyFirstBreakthroughAndSettlesAtFinalScore() {
        RoadRaceRoundState state = new RoadRaceRoundState();
        UUID playerId = UUID.randomUUID();

        state.observe(45_270);
        RoadRaceRoundState.Breakthrough breakthrough = state.recordImprovement(
                playerId,
                "Player",
                45_220,
                45_220,
                45_270).orElseThrow();
        assertTrue(breakthrough.globalBest());

        state.observe(45_320);
        assertTrue(state.recordImprovement(
                playerId,
                "Player",
                45_270,
                45_270,
                45_320).isEmpty());

        RoadRaceRoundState.Settlement settlement = state.finish().orElseThrow();
        assertEquals(45_220, settlement.previousPersonal());
        assertEquals(45_320, settlement.finalScore());
        assertTrue(state.finish().isEmpty());
    }

    @Test
    void detectsOldClientRoundTransitionFromScoreDrop() {
        RoadRaceRoundState state = new RoadRaceRoundState();
        state.observe(16_290);

        assertFalse(state.startsNewRound(16_340));
        assertTrue(state.startsNewRound(50));
    }
}
