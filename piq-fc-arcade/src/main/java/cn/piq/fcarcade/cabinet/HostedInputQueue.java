package cn.piq.fcarcade.cabinet;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded edges. Ordinary zero survives; forced release invalidates that port's backlog. */
final class HostedInputQueue {
    private final ArrayDeque<int[]> pending=new ArrayDeque<>();
    private int[] latest=new int[4];
    synchronized boolean offer(int[] masks){
        if(masks==null||masks.length!=4)throw new IllegalArgumentException("ports");
        for(int mask:masks)if((mask&~4095)!=0)throw new IllegalArgumentException("mask");
        if(Arrays.equals(masks,latest))return true;
        if(pending.size()>=128)return false;
        latest=masks.clone();pending.addLast(latest.clone());return true;
    }
    synchronized int[] poll(){return pending.pollFirst();}
    synchronized boolean coin(int port){
        if(pending.size()>126)return false;
        int[][] pulse=CabinetCoinPolicy.pulse(latest,port);
        pending.addLast(pulse[0]);pending.addLast(pulse[1]);latest=pulse[1].clone();return true;
    }
    synchronized void release(int port){
        if(port<0||port>3)throw new IllegalArgumentException("port");
        latest[port]=0;for(int[] state:pending)state[port]=0;
    }
    /**
     * Paid-room focus reset: immediately strip gameplay from queued states, but
     * keep each accepted coin press/release in its original FIFO position.
     * The caller must also use a coin-preserving release in the downstream core;
     * ordinary releasePort there would still erase already-forwarded paid edges.
     * No extra state is appended: coin() already atomically appends its release,
     * and the downstream release command clears currently applied gameplay.
     */
    synchronized void releasePreservingCoins(int port){
        if(port<0||port>3)throw new IllegalArgumentException("port");
        latest[port]=0;for(int[] state:pending)state[port]&=CabinetCoinPolicy.COIN_MASK;
    }
    synchronized void clear(){pending.clear();latest=new int[4];}
}
