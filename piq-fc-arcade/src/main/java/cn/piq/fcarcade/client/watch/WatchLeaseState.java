package cn.piq.fcarcade.client.watch;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Connection-scoped monotonic start watermark and independently addressable live leases.
 * Starts use the server's globally increasing revision on the ordered MC connection. A stop for
 * one lease never retires another source. Retired starts remain rejected without an unbounded
 * UUID tombstone set. Network callbacks must still check their captured connection. */
public final class WatchLeaseState {
    private static final Object LEGACY_SOURCE = new Object();
    private record Lease(Object source,long revision) {}
    private Object connection;
    private long revision;
    private final Map<UUID,Lease> leases = new HashMap<>();
    public boolean connection(Object next) {
        if (next == connection) return false;
        connection = next; revision = 0; leases.clear(); return true;
    }
    public boolean begin(long nextRevision, UUID nextLease) {
        return begin(LEGACY_SOURCE,nextRevision,nextLease);
    }
    public boolean begin(Object source,long nextRevision,UUID nextLease) {
        if (connection == null || nextLease == null || nextRevision <= revision) return false;
        if(source==null||leases.containsKey(nextLease))return false;
        boolean replaces=leases.values().stream().anyMatch(l->l.source().equals(source));
        if(!replaces&&leases.size()>=4)return false;
        leases.values().removeIf(l->l.source().equals(source));
        revision = nextRevision; leases.put(nextLease,new Lease(source,nextRevision)); return true;
    }
    public boolean matches(long incomingRevision, UUID incomingLease) {
        var live=leases.get(incomingLease);
        return connection!=null&&live!=null&&live.revision()==incomingRevision;
    }
    public void retire() { leases.clear(); }
    public void retire(UUID lease){leases.remove(lease);}
    public int size(){return leases.size();}
    public boolean stop(long incomingRevision, UUID incomingLease) {
        if(connection==null||incomingRevision<=0||incomingLease==null)return false;
        var live=leases.get(incomingLease);
        if(live!=null){
            if(live.revision()!=incomingRevision)return false;
            leases.remove(incomingLease);return true;
        }
        // A stop can precede its start in a captured main-thread callback. Tombstone that
        // revision, but never close any currently live lease with a different identity.
        if(incomingRevision<=revision)return false;
        revision=incomingRevision;return true;
    }
}
