package cn.piq.sfchome.server;
/** Per-port packet budget and a watchdog independent from a player's TCP connection. */
public final class SfcInputHealth {
    static final int NEUTRALIZE_AFTER_TICKS=12;
    private final long[] last={0,0},window={-1,-1};private final int[] packets={0,0};
    private final boolean[] neutralized={false,false};
    public void start(long tick){startPort(0,tick);startPort(1,tick);}
    public void startPort(int port,long tick){
        if(port<0||port>1)throw new IllegalArgumentException("Invalid controller port");
        last[port]=tick;window[port]=-1;packets[port]=0;neutralized[port]=false;
    }
    public boolean packet(int port,long tick){if(port<0||port>1||tick<last[port])return false;if(window[port]!=tick){window[port]=tick;packets[port]=0;}if(++packets[port]>64)return false;last[port]=tick;neutralized[port]=false;return true;}
    /** Stop a held button promptly without returning the physical controller for a brief stall. */
    public boolean neutralizeStalePort(int port,long tick){
        if(port<0||port>1)throw new IllegalArgumentException("Invalid controller port");
        if(neutralized[port]||tick-last[port]<NEUTRALIZE_AFTER_TICKS)return false;
        neutralized[port]=true;return true;
    }
    public boolean expiredPort(int port,long tick){if(port<0||port>1)throw new IllegalArgumentException("Invalid controller port");return tick-last[port]>100;}
    public boolean expired(long tick,boolean secondPort){return expiredPort(0,tick)||secondPort&&expiredPort(1,tick);}
}
