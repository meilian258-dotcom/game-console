package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectatorSelectionTest {
    @Test
    void defaultLimitSelectsTwoNearestSpectatorsPlusAllControllers() {
        assertEquals(Set.of(1L, 2L, 4L, 5L), SpectatorSelection.select(List.of(
                spectator(3, 8), spectator(2, 4), spectator(1, 2),
                controller(4), controller(5)), Set.of(),
                LocalArcadePreferences.DEFAULT_MAX_SPECTATORS));
    }

    @Test
    void zeroDisablesSpectatorsButNeverControllers() {
        assertEquals(Set.of(4L), SpectatorSelection.select(List.of(
                spectator(1, 1), spectator(2, 2), controller(4)), Set.of(1L, 2L), 0));
    }

    @Test
    void nearbyDistanceTieDoesNotThrashAlreadySelectedSession() {
        assertEquals(Set.of(2L), SpectatorSelection.select(List.of(
                spectator(1, 5.0), spectator(2, 5.4)), Set.of(2L), 1));
        assertEquals(Set.of(2L), SpectatorSelection.select(List.of(
                spectator(1, 4.2), spectator(2, 5.4)), Set.of(2L), 1));
    }

    @Test
    void clearlyNearerChallengerReplacesIncumbent() {
        assertEquals(Set.of(1L), SpectatorSelection.select(List.of(
                spectator(1, 3.0), spectator(2, 5.4)), Set.of(2L), 1));
    }

    @Test
    void vanishedSessionCannotKeepItsSlot() {
        assertEquals(Set.of(1L), SpectatorSelection.select(List.of(
                spectator(1, 3.0)), Set.of(2L), 1));
    }

    @Test
    void reducingOrIncreasingLimitTakesEffectOnNextSelection() {
        List<SpectatorSelection.Candidate> candidates = List.of(
                spectator(1, 1), spectator(2, 2), spectator(3, 3));
        assertEquals(Set.of(1L), SpectatorSelection.select(candidates, Set.of(1L, 2L), 1));
        assertEquals(Set.of(1L, 2L, 3L), SpectatorSelection.select(candidates, Set.of(1L), 8));
    }

    @Test
    void tiesAreDeterministicAndInvalidDistancesAreExcluded() {
        assertEquals(Set.of(1L), SpectatorSelection.select(List.of(
                spectator(2, 3), spectator(1, 3),
                new SpectatorSelection.Candidate(3, false, Double.NaN),
                new SpectatorSelection.Candidate(4, false, Double.POSITIVE_INFINITY),
                new SpectatorSelection.Candidate(5, false, -1)), Set.of(), 1));
    }

    @Test
    void invalidLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SpectatorSelection.select(List.of(), Set.of(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> SpectatorSelection.select(List.of(), Set.of(), 9));
    }

    private static SpectatorSelection.Candidate spectator(long id, double distance) {
        return new SpectatorSelection.Candidate(id, false, distance * distance);
    }

    private static SpectatorSelection.Candidate controller(long id) {
        return new SpectatorSelection.Candidate(id, true, Double.POSITIVE_INFINITY);
    }
}
