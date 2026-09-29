package cn.piq.fcarcade.furniture;

import java.util.*;

/** Per-level exact-generation bench entitlement. Closed halves never mint another item. */
public final class FurnitureLedger {
    public record Entry(UUID id, int x, int y, int z, int turns, String wood, boolean closed, int absent) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(wood);
            if (turns<0||turns>3||absent<0||absent>3) throw new IllegalArgumentException("entry");
        }
        Entry close() { return new Entry(id,x,y,z,turns,wood,true,absent); }
        Entry acknowledge(int part) { return new Entry(id,x,y,z,turns,wood,closed,absent|(1<<part)); }
    }
    private final Map<UUID,Entry> entries=new HashMap<>();
    public Entry get(UUID id) { return entries.get(id); }
    public Collection<Entry> entries() { return List.copyOf(entries.values()); }
    public boolean add(Entry entry) { return entries.putIfAbsent(entry.id,entry)==null; }
    public boolean claim(UUID id) {
        Entry entry=entries.get(id);
        if (entry==null||entry.closed) return false;
        entries.put(id,entry.close()); return true;
    }
    public void acknowledge(UUID id,int part) {
        if (part<0||part>1) throw new IllegalArgumentException("part");
        Entry entry=entries.get(id);
        if(entry==null||!entry.closed)return;
        entry=entry.acknowledge(part);
        if(entry.absent==3)entries.remove(id); else entries.put(id,entry);
    }
    public void cancel(UUID id) { entries.remove(id); }
}
