package cn.piq.fcarcade.cabinet;

/** Physical seat partition. Explicit one-player backends never expose a second seat or link. */
public final class CabinetSeats {
    private CabinetSeats() {}
    public static int physical(boolean dual) { return dual ? 2 : 1; }
    public static int linkedCapacity(boolean primaryDual, boolean secondaryDual) { return physical(primaryDual) + physical(secondaryDual); }
    /** Zero means unavailable; existing multi-player backends keep two seats when unlinked. */
    public static int capacity(int supported, boolean linked, boolean primaryDual, boolean secondaryDual) {
        return capacity(supported, linked, primaryDual, secondaryDual, false);
    }
    /** Portrait cabinets have one physical control panel, including when used without a link. */
    public static int capacity(int supported, boolean linked, boolean primaryDual, boolean secondaryDual, boolean portrait) {
        if (supported < 1 || supported > 4) return 0;
        int capacity = linked ? linkedCapacity(primaryDual, secondaryDual) : Math.min(portrait ? 1 : 2, supported);
        return capacity <= supported ? capacity : 0;
    }
    /** Wire topology validation does not require a registered client core. */
    public static boolean validCapacity(boolean primaryDual, boolean linked, boolean secondaryDual, int capacity) {
        return linked ? capacity == linkedCapacity(primaryDual, secondaryDual) : capacity == 1 || capacity == 2;
    }
    public static int first(boolean primaryDual, boolean linked, boolean primary) { return primary ? 0 : linked ? physical(primaryDual) : -1; }
    public static int end(boolean primaryDual, boolean linked, boolean primary, int capacity) { return primary ? linked ? physical(primaryDual) : capacity : linked ? capacity : -1; }
    public static boolean owns(boolean primaryDual, boolean linked, boolean primary, int capacity, int port) {
        return capacity >= (linked ? 2 : 1) && capacity <= (linked ? 4 : 2) && port >= 0
                && port >= first(primaryDual, linked, primary) && port < end(primaryDual, linked, primary, capacity);
    }
}
