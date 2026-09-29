package cn.piq.fcarcade.cabinet;

/** Exactly-one-frame core. Construction, stepping, state and close all belong to ONE worker. */
public interface CabinetSyncCore extends AutoCloseable {
    int MAX_STATE_BYTES=16*1024*1024;
    int maxPlayers();
    double targetFps();
    /** Include core build, settings and dependency content which can affect deterministic execution. */
    String compatibilityId();
    /** Full state digests remain every 300 frames; only host snapshot uploads use this interval. */
    default int snapshotIntervalFrames() { return 300; }
    CabinetFrame runFrame(int p1,int p2,int p3,int p4) throws Exception;
    byte[] saveState() throws Exception;
    void loadState(byte[] state) throws Exception;
    /** Bind an adapter's embedded native frame to the server's restore frame when supported. */
    default void loadState(byte[] state, long logicalFrame) throws Exception { loadState(state); }
    /** Optional cross-thread cancellation SIGNAL only. Never execute native/core calls here.
     * Legacy cores keep all work, including close(), on their owning worker. */
    default void requestClose() {}
    @Override void close();
}
