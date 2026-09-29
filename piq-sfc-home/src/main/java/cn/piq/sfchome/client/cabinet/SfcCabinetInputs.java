// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client.cabinet;

import java.util.ArrayDeque;

/** One complete state transition per native frame: a quick press/release never collapses to zero. */
final class SfcCabinetInputs {
    static final int CAPACITY=128;
    record Pair(int p1,int p2) {}
    private static final Pair NONE=new Pair(0,0);
    private final ArrayDeque<Pair> edges=new ArrayDeque<>();
    private Pair wanted=NONE,current=NONE;
    synchronized boolean offer(int p1,int p2){
        if(((p1|p2)&~4095)!=0)throw new IllegalArgumentException("SFC input must use twelve libretro bits");
        Pair next=new Pair(p1,p2);
        if(next.equals(wanted))return true;
        if(edges.size()>=CAPACITY)return false;
        edges.addLast(next);wanted=next;return true;
    }
    synchronized Pair nextFrame(){if(!edges.isEmpty())current=edges.removeFirst();return current;}
    /** A departed port cannot be resurrected by older complete-pair entries still in the queue. */
    synchronized void releasePort(int port){
        if(port<0||port>1)throw new IllegalArgumentException("SFC controller port must be 0 or 1");
        current=without(current,port);wanted=without(wanted,port);
        var retained=new ArrayDeque<Pair>(edges.size());
        for(Pair edge:edges)retained.addLast(without(edge,port));
        edges.clear();edges.addAll(retained);
    }
    private static Pair without(Pair pair,int port){return port==0?new Pair(0,pair.p2()):new Pair(pair.p1(),0);}
    synchronized void clear(){edges.clear();wanted=NONE;current=NONE;}
}
