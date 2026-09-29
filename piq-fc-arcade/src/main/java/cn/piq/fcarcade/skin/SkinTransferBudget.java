package cn.piq.fcarcade.skin;

/** Shared admission calculation for server uploads and retained download buffers. */
public final class SkinTransferBudget {
    private SkinTransferBudget() {}
    public static boolean canStart(int active, long retainedBytes, int requestedBytes) {
        return active >= 0 && active < SkinTransferLimits.MAX_TRANSFERS
                && retainedBytes >= 0 && requestedBytes > 0
                && requestedBytes <= SkinTransferLimits.MAX_PNG_BYTES
                && requestedBytes <= SkinTransferLimits.MAX_IN_FLIGHT_BYTES - retainedBytes;
    }
}
