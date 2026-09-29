package cn.piq.fcarcade.home;

/** Bounded operation IDs; request values are absolute, not replayable toggles. */
public final class TvRemoteSettingsPolicy {
    public static final int REFRESH = 0, SCANLINES = 1, VOLUME = 2, MUTE = 3,
            ANIMATION = 4, NO_SIGNAL_TONE = 5, OCCUPANCY = 6, APPROVAL = 7;
    private TvRemoteSettingsPolicy() {}
    public static boolean valid(int action, int value) {
        if (action < REFRESH || action > APPROVAL) return false;
        if (action == VOLUME) return value >= 0 && value <= 100;
        return value == 0 || value == 1;
    }
    public static boolean mayApply(int action, int value, boolean hasConsole, boolean administrator) {
        // Keep legacy IDs decodable so old requests receive an explicit rejection.
        // Console administration belongs to the debug tool, never the TV remote.
        return valid(action, value) && action != OCCUPANCY && action != APPROVAL;
    }
}
