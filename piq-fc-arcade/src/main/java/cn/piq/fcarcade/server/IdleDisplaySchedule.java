package cn.piq.fcarcade.server;

public final class IdleDisplaySchedule {
    static final int RECONCILE_INTERVAL_TICKS = 1_200;
    static final int DEFAULT_ROTATE_INTERVAL_TICKS = 60;

    private IdleDisplaySchedule() {}

    static boolean shouldReconcile(int tickCount, boolean initialized) {
        return !initialized
                || tickCount % RECONCILE_INTERVAL_TICKS == 0;
    }

    static boolean shouldRotate(int tickCount, boolean initialized) {
        return shouldRotate(
                tickCount,
                initialized,
                DEFAULT_ROTATE_INTERVAL_TICKS);
    }

    static boolean shouldRotate(
            int tickCount,
            boolean initialized,
            int intervalTicks
    ) {
        return initialized && tickCount % Math.max(1, intervalTicks) == 0;
    }

    static int windowStart(int tickCount, int entryCount, int displayLimit) {
        return pageStart(
                tickCount,
                entryCount,
                displayLimit,
                DEFAULT_ROTATE_INTERVAL_TICKS);
    }

    static int windowStart(
            int tickCount,
            int entryCount,
            int displayLimit,
            int intervalTicks
    ) {
        return pageStart(
                tickCount,
                entryCount,
                displayLimit,
                intervalTicks);
    }

    /** Returns page starts 0, 3, 6, 9 instead of a sliding 0, 1, 2 window. */
    public static int pageStart(
            int tickCount,
            int entryCount,
            int displayLimit,
            int intervalTicks
    ) {
        if (entryCount <= 0 || displayLimit <= 0) return 0;
        int pageCount = (entryCount + displayLimit - 1) / displayLimit;
        if (pageCount <= 1) return 0;
        int safeInterval = Math.max(1, intervalTicks);
        int page = Math.floorMod(
                Math.floorDiv(tickCount, safeInterval),
                pageCount);
        return page * displayLimit;
    }
}
