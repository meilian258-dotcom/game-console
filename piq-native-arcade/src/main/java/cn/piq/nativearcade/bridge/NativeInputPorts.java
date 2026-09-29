package cn.piq.nativearcade.bridge;

import java.util.ArrayDeque;

/** Four independent bounded transition FIFOs, sampled together at the native frame boundary. */
public final class NativeInputPorts {
    public static final int PORTS=4,MAX_EDGES=128;
    private final ArrayDeque<Integer>[] pending;
    private final int[] held=new int[PORTS],latest=new int[PORTS];
    @SuppressWarnings("unchecked") public NativeInputPorts(){pending=new ArrayDeque[PORTS];for(int p=0;p<PORTS;p++)pending[p]=new ArrayDeque<>();}
    public static void checkPort(int port){if(port<0||port>=PORTS)throw new IllegalArgumentException("Controller port outside 0..3");}
    public static void checkMask(int mask){if((mask&~0xffff)!=0)throw new IllegalArgumentException("Inputs must be libretro 16-bit masks");}
    public synchronized boolean offer(int p1,int p2,int p3,int p4){
        int[] values={p1,p2,p3,p4};for(int v:values)checkMask(v);
        // Failure is atomic; the owner stops the whole core rather than applying a partial input batch.
        for(int p=0;p<PORTS;p++)if(values[p]!=latest[p]&&pending[p].size()>=MAX_EDGES)return false;
        for(int p=0;p<PORTS;p++)if(values[p]!=latest[p]){latest[p]=values[p];pending[p].addLast(values[p]);}
        return true;
    }
    public synchronized int[] nextFrame(){for(int p=0;p<PORTS;p++){Integer next=pending[p].pollFirst();if(next!=null)held[p]=next;}return held.clone();}
    public synchronized void releasePort(int port){checkPort(port);pending[port].clear();held[port]=latest[port]=0;}
    /** Projects the original FIFO onto coin bit 2; no new coin or replay is synthesized. */
    public synchronized void releaseGameplayPortKeepingCoin(int port){
        checkPort(port);held[port]&=4;int previous=held[port];
        var retained=new ArrayDeque<Integer>();
        for(int mask:pending[port]){int coin=mask&4;if(coin!=previous){retained.addLast(coin);previous=coin;}}
        pending[port].clear();pending[port].addAll(retained);latest[port]=previous;
    }
    public synchronized void clear(){for(int p=0;p<PORTS;p++)releasePort(p);}
    public synchronized int pending(int port){checkPort(port);return pending[port].size();}
}
