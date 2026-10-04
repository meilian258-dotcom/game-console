// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.watch;

import java.util.Collection;
import java.util.Comparator;
import java.util.function.IntSupplier;

/** Pure bounded presentation policy. Neither capacity nor audio selection grants authority. */
final class WatchPresentationPolicy {
    private WatchPresentationPolicy(){}
    /** Native free count includes slots not yet acquired by preparing Java watch claims. */
    static int capacity(int freeNative,int heldByLiveWatches,int unreservedClosingClaims){
        return capacity(freeNative,heldByLiveWatches,unreservedClosingClaims,0);
    }
    static int capacity(int freeNative,int heldByLiveWatches,int unreservedClosingClaims,int pendingControlClaims){
        // A new controller may reserve unused/preparing capacity, but cannot evict an already
        // running unrelated observer. At four held watches it must report full and retry later.
        return Math.max(0,Math.min(4,Math.max(heldByLiveWatches,
                freeNative+heldByLiveWatches-unreservedClosingClaims-pendingControlClaims)));
    }
    static int pendingControls(Collection<IntSupplier> queries){
        int count=0;
        for(var query:queries)try{count+=Math.max(0,Math.min(4,query.getAsInt()));}
        catch(RuntimeException|LinkageError unavailable){return 4;}
        return Math.min(4,count);
    }
    record Audible<T>(T key,double distance,boolean ready) {}
    static <T> T primary(Collection<Audible<T>> sources,boolean operating){
        if(operating)return null;
        return sources.stream().filter(s->s.ready()&&Double.isFinite(s.distance())&&s.distance()>=0)
                .min(Comparator.comparingDouble(Audible::distance)).map(Audible::key).orElse(null);
    }
}
