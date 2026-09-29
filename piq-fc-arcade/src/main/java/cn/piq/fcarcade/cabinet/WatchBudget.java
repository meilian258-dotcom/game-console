package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Complete-recipient-frame rolling egress budget; ticks are server ticks, not client supplied time. */
public final class WatchBudget {
    public static final int SOURCE_BYTES=2*1024*1024, GLOBAL_BYTES=8*1024*1024;
    private static final class Window {
        final long[] ticks=new long[20];final int[] bytes=new int[20];
        long used(long now){long total=0;for(int i=0;i<20;i++)if(now>=ticks[i]&&now-ticks[i]<20)total+=bytes[i];return total;}
        void add(long now,int amount){int slot=(int)(now%20);if(ticks[slot]!=now){ticks[slot]=now;bytes[slot]=0;}bytes[slot]+=amount;}
    }
    private final Window global=new Window();
    private final Map<UUID,Window> sources=new HashMap<>();
    public boolean tryReserve(UUID source,long now,int bytes) {
        Objects.requireNonNull(source);if(now<0||bytes<=0||bytes>SOURCE_BYTES)return false;
        var window=sources.get(source);
        if(window==null){if(sources.size()>=WatchLedger.MAX_SOURCES)return false;window=new Window();sources.put(source,window);}
        if(global.used(now)+bytes>GLOBAL_BYTES||window.used(now)+bytes>SOURCE_BYTES)return false;
        global.add(now,bytes);window.add(now,bytes);return true;
    }
    /** Keep the global charge after source removal, preventing restart from refunding real egress. */
    public void remove(UUID source) { sources.remove(source); }
}
