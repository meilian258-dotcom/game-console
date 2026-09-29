package cn.piq.fcarcade.cabinet;

/** Reserve the latter half of bounded history for the host to refresh its cached snapshot.
 * A slow guest may not monopolize an old cache until the entire running room expires. */
public final class CabinetSyncRecoveryWindow {
    public static final int MAX_STATE_AGE=CabinetSyncTimeline.MAX_HISTORY/2;
    public static final int MAX_ACTIVATION_LAG=120;
    private CabinetSyncRecoveryWindow(){}
    public static boolean available(long authoritativeFrame,long snapshotFrame){
        return snapshotFrame>=0&&authoritativeFrame>=snapshotFrame&&authoritativeFrame-snapshotFrame<MAX_STATE_AGE;
    }
    /** Allow bounded network transit, not an acknowledgement of a long-obsolete restore goal. */
    public static boolean canActivate(long authoritativeFrame,long acknowledgedFrame){
        return acknowledgedFrame>=0&&authoritativeFrame>=acknowledgedFrame&&authoritativeFrame-acknowledgedFrame<=MAX_ACTIVATION_LAG;
    }
}
