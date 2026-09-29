package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TvRemotePolicyTest {
    @Test void normalPlayersDoNotNeedAnOperatorFlag() {
        assertTrue(TvRemotePolicy.canActivate(true, true, false, true, true, false, false));
    }
    @Test void serverThreadIsRequired() {
        assertFalse(TvRemotePolicy.canActivate(false, true, false, true, true, false, false));
    }
    @Test void deathAndDisconnectRejectBeforeAnyMutation() {
        assertFalse(TvRemotePolicy.canActivate(true, false, false, true, true, false, false));
        assertFalse(TvRemotePolicy.canActivate(true, true, false, false, true, false, false));
    }
    @Test void spectatorAndWrongItemAreIndependentVetoes() {
        assertFalse(TvRemotePolicy.canActivate(true, true, true, true, true, false, false));
        assertFalse(TvRemotePolicy.canActivate(true, true, false, true, false, false, false));
    }
    @Test void releasingTheButtonDoesNotBypassCooldownAndCooldownDoesNotBypassHoldLatch() {
        assertFalse(TvRemotePolicy.canActivate(true, true, false, true, true, true, false));
        assertFalse(TvRemotePolicy.canActivate(true, true, false, true, true, false, true));
        assertFalse(TvRemotePolicy.canActivate(true, true, false, true, true, true, true));
        assertTrue(TvRemotePolicy.canActivate(true, true, false, true, true, false, false));
    }
    @Test void eightBlockDistanceIncludesExactBoundaryOnly() {
        assertTrue(TvRemotePolicy.inRange(0));
        assertTrue(TvRemotePolicy.inRange(64));
        assertFalse(TvRemotePolicy.inRange(Math.nextUp(64.0)));
        assertFalse(TvRemotePolicy.inRange(81));
    }
    @Test void nonFiniteAndNegativeDistanceCannotPassTheRayGate() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1})
            assertFalse(TvRemotePolicy.inRange(value));
    }
    @Test void cooldownIsEightTicksAndHoldWindowIsLongerThanRepeatedInput() {
        assertEquals(8, TvRemotePolicy.COOLDOWN_TICKS);
        assertEquals(8.0, TvRemotePolicy.RANGE);
        assertEquals(72_000, TvRemotePolicy.HOLD_TICKS);
    }
}
