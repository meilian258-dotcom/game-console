package cn.piq.fcarcade.client;

import java.util.*;

/** At most two live physical devices can share one home session; returning to a gun
 * must not reset its button sequence while its separate aiming sequence stays live. */
public final class HomeInputSequences {
    private final LinkedHashMap<UUID,Integer> saved=new LinkedHashMap<>(4,.75F,true);
    public int switchLease(UUID previous,int nextSequence,UUID next){
        if(previous!=null){
            saved.put(previous,nextSequence);
            while(saved.size()>2)saved.remove(saved.keySet().iterator().next());
        }
        return next==null?0:saved.getOrDefault(next,0);
    }
    public void clear(){saved.clear();}
    int size(){return saved.size();}
}
