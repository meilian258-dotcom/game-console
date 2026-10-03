package cn.piq.fcarcade.home.content;

import java.util.*;

/** Server-thread read-only transfer registry; separate from the single controlled runtime. */
final class ContentCardReadTransfers<T> {
    static final int PER_PLAYER=4,GLOBAL=16;
    static final long MAX_RESERVED=64L*1024*1024;
    private record Key(UUID player,UUID token) {}
    record Entry<T>(UUID player,UUID token,Object connection,int bytes,T value) {}
    private final Map<Key,Entry<T>> entries=new LinkedHashMap<>();
    boolean add(UUID player,UUID token,Object connection,int bytes,T value,long otherReserved){
        Objects.requireNonNull(player);Objects.requireNonNull(token);Objects.requireNonNull(connection);Objects.requireNonNull(value);
        if(bytes<1||bytes>ContentCardStore.MAX_BYTES||otherReserved<0||otherReserved>MAX_RESERVED)return false;
        var key=new Key(player,token);
        if(entries.containsKey(key)||entries.size()>=GLOBAL||entries.values().stream().filter(e->e.player.equals(player)).count()>=PER_PLAYER
                ||reservedBytes()+otherReserved+bytes>MAX_RESERVED)return false;
        entries.put(key,new Entry<>(player,token,connection,bytes,value));return true;
    }
    T get(UUID player,UUID token,Object connection){var entry=entries.get(new Key(player,token));return entry!=null&&entry.connection==connection?entry.value:null;}
    boolean remove(UUID player,UUID token,T value){var key=new Key(player,token);var entry=entries.get(key);return entry!=null&&entry.value==value&&entries.remove(key,entry);}
    long reservedBytes(){long total=0;for(var entry:entries.values())total+=entry.bytes;return total;}
    List<Entry<T>> snapshot(){return List.copyOf(entries.values());}
    void clear(){entries.clear();}
}
