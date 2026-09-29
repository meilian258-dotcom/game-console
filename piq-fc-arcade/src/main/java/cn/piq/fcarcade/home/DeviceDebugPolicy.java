package cn.piq.fcarcade.home;

/** Pure bounds shared by the OP diagnostic tool and capability-scoped home settings. */
public final class DeviceDebugPolicy {
    public static final double RANGE = 6;
    public static final int COOLDOWN_TICKS = 8;
    public static final int HOLD_TICKS = 72_000;
    public static final int REQUEST_TICKS = 3;
    private DeviceDebugPolicy() {}
    public static boolean matchesToken(java.util.UUID supplied,java.util.UUID current) {
        return supplied != null && supplied.equals(current);
    }

    public static boolean validRequest(int revision,int mode,int occupancy,int approval) {
        return revision >= 0 && mode >= -1 && mode <= 4 && occupancy >= -1 && occupancy <= 1
                && approval >= -1 && approval <= 1
                && (mode >= 0 ? 1 : 0) + (occupancy >= 0 ? 1 : 0) + (approval >= 0 ? 1 : 0) <= 1;
    }
    public static boolean canActivate(boolean serverThread,boolean alive,boolean spectator,boolean connected,
                                      boolean operator,boolean holding,boolean cooldown,boolean using) {
        return serverThread && alive && !spectator && connected && operator && holding && !cooldown && !using;
    }
    public static boolean inRange(double squared,double interactionRange) {
        double range = Math.min(RANGE,interactionRange);
        return Double.isFinite(squared) && Double.isFinite(interactionRange) && Double.isFinite(range) && range > 0 && squared >= 0 && squared <= range * range;
    }
    public static boolean mayEdit(boolean admin,boolean busy,boolean occupancySupported,int mode,int occupancy,int approval,int supported) {
        if (!validRequest(0,mode,occupancy,approval) || !admin || busy) return false;
        return mode >= 0 ? (supported & (1 << mode)) != 0 : occupancy < 0 || occupancySupported;
    }
}
