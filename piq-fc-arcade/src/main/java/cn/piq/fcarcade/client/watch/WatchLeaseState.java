package cn.piq.fcarcade.client.watch;

import java.util.UUID;

/** Connection-scoped monotonic tombstone: unlimited legitimate switching, constant memory. */
public final class WatchLeaseState {
    private Object connection;
    private long revision;
    private UUID lease;
    public boolean connection(Object next) {
        if (next == connection) return false;
        connection = next; revision = 0; lease = null; return true;
    }
    public boolean begin(long nextRevision, UUID nextLease) {
        if (connection == null || nextLease == null || nextRevision <= revision) return false;
        revision = nextRevision; lease = nextLease; return true;
    }
    public boolean matches(long incomingRevision, UUID incomingLease) {
        return connection != null && lease != null && revision == incomingRevision && lease.equals(incomingLease);
    }
    public void retire() { lease = null; }
    public boolean stop(long incomingRevision, UUID incomingLease) {
        if (connection == null || incomingRevision < revision || incomingLease == null) return false;
        if (incomingRevision == revision && lease != null && !lease.equals(incomingLease)) return false;
        revision = incomingRevision; lease = null; return true;
    }
}
