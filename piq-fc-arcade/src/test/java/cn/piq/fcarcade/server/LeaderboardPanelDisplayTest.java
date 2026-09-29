package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaderboardPanelDisplayTest {
    private static final double EPSILON = 0.000_001D;

    @Test
    void zeroDepthSitsJustOutsideTheTwoSixteenthPanelFace() {
        assertEquals(
                -0.3725D,
                LeaderboardPanelPlacement.outwardOffset(0),
                EPSILON);
    }

    @Test
    void positiveDepthMovesTextOutwardAlongThePanelNormal() {
        double flush = LeaderboardPanelPlacement.outwardOffset(0);
        double outward = LeaderboardPanelPlacement.outwardOffset(5);

        assertEquals(flush + 0.05D, outward, EPSILON);
    }
}
