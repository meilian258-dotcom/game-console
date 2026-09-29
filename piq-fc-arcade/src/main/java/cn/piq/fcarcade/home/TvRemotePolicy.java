package cn.piq.fcarcade.home;

/** Pure remote-control limits. There is deliberately no operator requirement. */
public final class TvRemotePolicy {
    public static final double RANGE = 8.0;
    public static final int COOLDOWN_TICKS = 8;
    public static final int HOLD_TICKS = 72_000;

    private TvRemotePolicy() {}

    public static boolean canActivate(boolean serverThread, boolean alive, boolean spectator,
                                      boolean connected, boolean holdingRemote, boolean coolingDown,
                                      boolean usingItem) {
        return serverThread && alive && !spectator && connected && holdingRemote && !coolingDown && !usingItem;
    }

    public static boolean inRange(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= RANGE * RANGE;
    }
}
