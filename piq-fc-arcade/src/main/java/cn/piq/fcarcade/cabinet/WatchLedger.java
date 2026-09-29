package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Pure observation authority. Connection comparison is object identity, not equals or player UUID alone. */
public final class WatchLedger {
    public static final int MAX_SOURCES=8, MAX_VIEWERS=8, TIMEOUT=100;
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
    private final Map<UUID,Lease> leases=new LinkedHashMap<>();
    private long revision;
    public Lease get(UUID player) { return leases.get(player); }
    public List<Lease> all() { return List.copyOf(leases.values()); }
    public int count(Source source) { int n=0;for(var lease:leases.values())if(lease.source.equals(source))n++;return n; }
    public Lease select(UUID player,Object connection,List<Candidate> candidates,boolean eligible,long now) {
        Objects.requireNonNull(player);Objects.requireNonNull(connection);Objects.requireNonNull(candidates);
        if(now<0)throw new IllegalArgumentException("watch time");
        if(candidates.size()>MAX_SOURCES)throw new IllegalArgumentException("source limit");
        var old=leases.get(player);
        if(old!=null&&(old.connection!=connection||now>=old.expires||!eligible)){leases.remove(player);old=null;}
        if(!eligible)return null;
        if(old!=null)for(var candidate:candidates)if(candidate.source.equals(old.source)&&candidate.distanceSquared<=candidate.exitSquared)return old;
        leases.remove(player);
        Candidate best=null;var occupiedSources=new HashSet<Source>();for(var lease:leases.values())occupiedSources.add(lease.source);
        for(var candidate:candidates)if(candidate.distanceSquared<=candidate.enterSquared&&count(candidate.source)<MAX_VIEWERS
                &&(occupiedSources.contains(candidate.source)||occupiedSources.size()<MAX_SOURCES)
                &&(best==null||candidate.distanceSquared<best.distanceSquared
                ||candidate.distanceSquared==best.distanceSquared&&candidate.source.id.compareTo(best.source.id)<0))best=candidate;
        if(best==null)return null;
        if(revision==Long.MAX_VALUE)return null; // Exhaustion is fail-closed; never recycle a revision.
        var next=new Lease(player,connection,UUID.randomUUID(),best.source,++revision,now+TIMEOUT);leases.put(player,next);return next;
    }
    public boolean heartbeat(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=authorized(player,connection,token,expectedRevision,now);if(lease==null)return false;
        leases.put(player,new Lease(player,connection,token,lease.source,lease.revision,now+TIMEOUT));return true;
    }
    public Lease authorized(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=leases.get(player);return now>=0&&lease!=null&&lease.connection==connection&&lease.token.equals(token)
                &&lease.revision==expectedRevision&&now<lease.expires?lease:null;
    }
    public Lease release(UUID player,Object connection,UUID token,long expectedRevision,long now) {
        var lease=authorized(player,connection,token,expectedRevision,now);if(lease!=null)leases.remove(player);return lease;
    }
    public Lease remove(UUID player) { return leases.remove(player); }
    public List<Lease> removeSource(Source source) {
        var removed=new ArrayList<Lease>();for(var lease:all())if(lease.source.equals(source)){leases.remove(lease.player);removed.add(lease);}return List.copyOf(removed);
    }
}
