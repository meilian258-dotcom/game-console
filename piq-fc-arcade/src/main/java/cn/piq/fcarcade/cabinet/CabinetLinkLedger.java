package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded persistent pair identity. No coordinate alone can disconnect or impersonate an endpoint. */
final class CabinetLinkLedger {
    static final int MAX_PAIRS=128;
    private static final Pattern DIMENSION=Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    record End(String dimension,int x,int y,int z,UUID identity,boolean dual){
        End{
            if(dimension==null||dimension.length()>128||!DIMENSION.matcher(dimension).matches()||identity==null)
                throw new IllegalArgumentException("Invalid cabinet link endpoint");
        }
        boolean samePlace(End other){return dimension.equals(other.dimension)&&x==other.x&&y==other.y&&z==other.z;}
    }
    record Pair(UUID id,End primary,End secondary){
        Pair{Objects.requireNonNull(id);Objects.requireNonNull(primary);Objects.requireNonNull(secondary);}
        boolean owns(End end){return primary.equals(end)||secondary.equals(end);}
        End other(End end){return primary.equals(end)?secondary:secondary.equals(end)?primary:null;}
    }
    private final Map<UUID,Pair> pairs=new LinkedHashMap<>();
    static boolean compatible(End a,End b){
        if(a==null||b==null||!a.dimension.equals(b.dimension)||a.identity.equals(b.identity)||a.samePlace(b))return false;
        double dx=(double)a.x-b.x,dy=(double)a.y-b.y,dz=(double)a.z-b.z;
        return dx*dx+dy*dy+dz*dz<=16*16;
    }
    private boolean occupied(End end){
        for(Pair pair:pairs.values())for(End old:List.of(pair.primary,pair.secondary))
            if(old.identity.equals(end.identity)||old.samePlace(end))return true;
        return false;
    }
    Pair find(End end){for(Pair pair:pairs.values())if(pair.owns(end))return pair;return null;}
    List<Pair> snapshot(){return List.copyOf(pairs.values());}
    Pair connect(End first,End second,boolean authorized,boolean idle,int primaryPorts){
        if(!authorized||!idle||!compatible(first,second)||primaryPorts<CabinetSeats.linkedCapacity(first.dual,second.dual)
                ||primaryPorts>4||pairs.size()>=MAX_PAIRS||occupied(first)||occupied(second))return null;
        Pair pair=new Pair(UUID.randomUUID(),first,second);pairs.put(pair.id,pair);return pair;
    }
    boolean restore(Pair pair){
        if(pair==null||pairs.size()>=MAX_PAIRS||pairs.containsKey(pair.id)||!compatible(pair.primary,pair.secondary)
                ||occupied(pair.primary)||occupied(pair.secondary))return false;
        pairs.put(pair.id,pair);return true;
    }
    Pair disconnect(End end,boolean authorized,boolean idle){
        if(!authorized||!idle)return null;return remove(end);
    }
    Pair remove(End end){Pair pair=find(end);if(pair!=null)pairs.remove(pair.id);return pair;}
}
