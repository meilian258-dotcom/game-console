package cn.piq.fcarcade.config;

/** Pure bounds and placement-only resolution; -1 means preserve the model's automatic default. */
public final class GameConsoleAdminPolicy {
    public static final int DEFAULT_RANGE = 16, MIN_RANGE = 4, MAX_RANGE = 64, EXIT_MARGIN = 4;
    // Capabilities include RetroArch Netplay (bit 3) and JNI Netplay (bit 4), while global
    // placement defaults and CabinetSyncMode still select only the three legacy lanes.
    private static final int KNOWN_CAPABILITIES = 0b11111;
    private GameConsoleAdminPolicy() {}
    public static boolean validMode(int mode) { return mode >= -1 && mode <= 2; }
    public static boolean validRange(int range) { return range >= MIN_RANGE && range <= MAX_RANGE; }
    /** Applied only to new FC placements; explicit global modes and old NBT are preserved. */
    public static boolean defaultFcJni(int preferred,int supported) {
        if(!validMode(preferred)||(supported&~KNOWN_CAPABILITIES)!=0)throw new IllegalArgumentException("Invalid console defaults");
        return preferred==-1&&(supported&16)!=0;
    }
    public static int selectNewMode(int preferred, int supported, int fallback) {
        if (!validMode(preferred) || fallback < 0 || fallback > 2 || (supported & ~KNOWN_CAPABILITIES) != 0)
            throw new IllegalArgumentException("Invalid console defaults");
        return preferred >= 0 && (supported & (1 << preferred)) != 0 ? preferred : fallback;
    }
    public static int persistedRange(int value, int fallback) { return validRange(value) ? value : fallback; }
    public static int exitRange(int range) {
        if (!validRange(range)) throw new IllegalArgumentException("Invalid observation range");
        return range + EXIT_MARGIN;
    }
}
