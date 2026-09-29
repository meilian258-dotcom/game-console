package cn.piq.fcarcade.server;

/** Pure placement math shared by the panel renderer and unit tests. */
final class LeaderboardPanelPlacement {
    /**
     * The panel occupies the two sixteenths nearest its supporting wall. Its
     * visible face is 0.375 blocks behind the block centre along the outward
     * direction. A tiny positive bias keeps text in front of the surface.
     */
    private static final double PANEL_FACE_OFFSET = -0.3725D;

    private LeaderboardPanelPlacement() {
    }

    static double outwardOffset(int depthHundredths) {
        return PANEL_FACE_OFFSET + depthHundredths / 100.0D;
    }
}
