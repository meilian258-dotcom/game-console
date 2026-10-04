package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Pure observation authority. Connection comparison is object identity, not equals or player UUID alone. */
public final class WatchLedger {
    public static final int MAX_SOURCES=8, MAX_VIEWERS=8, MAX_PLAYER_SOURCES=4, TIMEOUT=100;
    public static final double ENTER_SQUARED=16*16, EXIT_SQUARED=20*20;
    public record Source(UUID id,UUID hostLease) {
        public Source { Objects.requireNonNull(id); Objects.requireNonNull(hostLease); }
    }
    public record Candidate(Source source,double distanceSquared,double enterSquared,double exitSquared) {
        public Candidate(Source source,double distanceSquared){this(source,distanceSquared,ENTER_SQUARED,EXIT_SQUARED);}
        public Candidate {
            Objects.requireNonNull(source);
            if(!Double.isFinite(distanceSquared)||distanceSquared<0)throw new IllegalArgumentException("distance");
            if(!Double.isFinite(enterSquared)||!Double.isFinite(exitSquared)||enterSquared<1
                    ||enterSquared>128*128||exitSquared<enterSquared||exitSquared>132*132)throw new IllegalArgumentException("watch range");
        }
    }
    public record Lease(UUID player,Object connection,UUID token,Source source,long revision,long expires) {}
    // Key by unforgeable watch token, not player: one connection may watch several sources.
    private final Map<UUID,Lease> leases=new LinkedHashMap<>();
    private long revision;
    /** Legacy single-source accessor. Multi-source callers must use all(player) or authorized. */
    public Lease get(UUID player) { return leases.values().stream().filter(l->l.player.equals(player)).findFirst().orElse(null); }
    public List<Lease> all() { return List.copyOf(leases.values()); }
    public List<Lease> all(UUID player) { return leases.values().stream().filter(l->l.player.equals(player)).toList(); }
    public int count(Source source) { int n=0;for(var lease:leases.values())if(lease.source.equals(source))n++;return n; }
    public Lease select(UUID player,Object connection,List<Candidate> candidates,boolean eligible,long now) {
        var selected=selectMany(player,connection,candidates,eligible?1:0,now);
        return selected.isEmpty()?null:selected.getFirst();
    }
    /** Select a homogeneous lane: callers must keep MEDIA at one and never mix it with Netplay.
     * Existing valid leases retain priority; each new source has its own token, revision and TTL. */
    public List<Lease> selectMany(UUID player,Object connection,List<Candidate> candidates,int capacity,long now) {
        Objects.requireNonNull(player);Objects.requireNonNull(connection);Objects.requireNonNull(candidates);
        if(now<0)throw new IllegalArgumentException("watch time");
        if(capacity<0||capacity>MAX_PLAYER_SOURCES)throw new IllegalArgumentException("watch capacity");
        if(candidates.size()>MAX_SOURCES)throw new IllegalArgumentException("source limit");
        var retained=new ArrayList<Lease>();var selectedSources=new HashSet<Source>();
        for(var old:all(player)){
            boolean valid=old.connection==connection&&now<old.expires&&retained.size()<capacity
                    &&candidates.stream().anyMatch(c->c.source.equals(old.source)&&c.distanceSquared<=c.exitSquared);
            if(valid){retained.add(old);selectedSources.add(old.source);}else leases.remove(old.token);
        }
        var occupiedSources=new HashSet<Source>();for(var lease:leases.values())occupiedSources.add(lease.source);
        var sorted=new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(c->c.source.id).thenComparing(c->c.source.hostLease));
        for(var candidate:sorted){
            if(retained.size()>=capacity||revision==Long.MAX_VALUE)break; // Never recycle an exhausted revision.
            if(selectedSources.contains(candidate.source)||candidate.distanceSquared>candidate.enterSquared||count(candidate.source)>=MAX_VIEWERS
                    ||!occupiedSources.contains(candidate.source)&&occupiedSources.size()>=MAX_SOURCES)continue;
            var next=new Lease(player,connection,UUID.randomUUID(),candidate.source,++revision,now+TIMEOUT);
            leases.put(next.token,next);retained.add(next);selectedSources.add(candidate.source);occupiedSources.add(candidate.source);
        }
        return List.copyOf(retained);
    }
    public boolean heartbeat(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=authorized(player,connection,token,expectedRevision,now);if(lease==null)return false;
        leases.put(token,new Lease(player,connection,token,lease.source,lease.revision,now+TIMEOUT));return true;
    }
    public Lease authorized(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=leases.get(token);return now>=0&&lease!=null&&lease.player.equals(player)&&lease.connection==connection
                &&lease.revision==expectedRevision&&now<lease.expires?lease:null;
    }
    public Lease release(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=authorized(player,connection,token,expectedRevision,now);if(lease!=null)leases.remove(token);return lease;
    }
    /** Legacy whole-player removal. The return value is the first removed lease only. */
    public Lease remove(UUID player) { var removed=removeAll(player);return removed.isEmpty()?null:removed.getFirst(); }
    public List<Lease> removeAll(UUID player) {var removed=all(player);for(var lease:removed)leases.remove(lease.token);return removed;}
    public Lease remove(Lease lease) {
        if(lease==null)return null;var current=leases.get(lease.token);
        return current!=null&&current.player.equals(lease.player)&&current.connection==lease.connection&&current.revision==lease.revision
                &&current.source.equals(lease.source)?leases.remove(lease.token):null;
    }
    public List<Lease> removeSource(Source source) {
        var removed=new ArrayList<Lease>();for(var lease:all())if(lease.source.equals(source)){leases.remove(lease.token);removed.add(lease);}return List.copyOf(removed);
    }
}
