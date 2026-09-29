package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.util.function.*;

/** Bounded metadata retries only. Does not bypass the physical connection's send window. */
public final class CabinetLibraryReplyQueue<K,V> {
    public static final int MAX_PENDING=64;
    public static final long RETRY_NANOS=250_000_000L, TIMEOUT_NANOS=5_000_000_000L;
    private static final class Pending<V> {
        final V value;final long began;long last;
        Pending(V value,long now){this.value=value;began=last=now;}
    }
    private final Map<K,Pending<V>> entries=new LinkedHashMap<>();
    public boolean offer(K key,V value,long now){
        Objects.requireNonNull(key);Objects.requireNonNull(value);
        if(!entries.containsKey(key)&&entries.size()>=MAX_PENDING)return false;
        entries.put(key,new Pending<>(value,now));return true;
    }
    public void remove(K key){entries.remove(key);}
    public int size(){return entries.size();}
    public void tick(long now,Predicate<V> valid,Predicate<V> send,Consumer<V> expired){
        var iterator=entries.values().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();
            if(!valid.test(entry.value)){iterator.remove();continue;}
            if(now-entry.began>=TIMEOUT_NANOS){iterator.remove();expired.accept(entry.value);continue;}
            if(now-entry.last<RETRY_NANOS)continue;
            entry.last=now;if(send.test(entry.value))iterator.remove();
        }
    }
}
