package cn.piq.sfchome.client;

import java.util.LinkedHashMap;

/** Two worker-produced immutable snapshots; request handling never serializes the live host core. */
final class SfcCheckpoints {
    record Entry(int frame,byte[] bytes,String sha){}
    private final LinkedHashMap<Integer,Entry> entries=new LinkedHashMap<>();
    synchronized Entry put(int frame,byte[] bytes){if(frame<0)throw new IllegalArgumentException("Checkpoint frame");SfcExecutionCore.validate(bytes);
        Entry e=new Entry(frame,bytes,SfcExecutionCore.digest(bytes));entries.put(frame,e);while(entries.size()>2)entries.remove(entries.keySet().iterator().next());return e;}
    synchronized Entry get(int frame){return entries.get(frame);}
    synchronized void clear(){entries.clear();}
}
