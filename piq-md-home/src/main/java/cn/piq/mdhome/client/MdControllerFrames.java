// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

/** Bounded visual-only snapshot. Never feeds the core; pulse latching cannot extend game input. */
final class MdControllerFrames {
    private final int[] held=new int[2],pending=new int[2];
    private long revision;
    synchronized void complete(long expected,int p1,int p2){
        if(expected!=revision)return;
        offer(0,p1);offer(1,p2);
    }
    synchronized void offer(int port,int mask){
        check(port,mask);pending[port]|=mask&~held[port];held[port]=mask;
    }
    synchronized int present(int port){check(port,0);int result=held[port]|pending[port];pending[port]=0;return result;}
    synchronized void clear(long next){revision=next;held[0]=held[1]=pending[0]=pending[1]=0;}
    synchronized void release(int port,long next){check(port,0);revision=next;held[port]=pending[port]=0;}
    private static void check(int port,int mask){if(port<0||port>1||(mask&~4095)!=0)throw new IllegalArgumentException("MD visual input");}
}
