// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import java.util.ArrayDeque;

/** Access serialized by MdEngine; queues preserve each port's short transitions independently. */
final class MdPublicInputBuffer {
    private final int ports;
    private final ArrayDeque<Integer>[] pending;
    private final int[] offered, held;
    private final boolean[] neutral;
    @SuppressWarnings("unchecked") MdPublicInputBuffer(int ports) {
        if (ports < 1 || ports > 2) throw new IllegalArgumentException("MD input ports");
        this.ports=ports; pending=(ArrayDeque<Integer>[])new ArrayDeque<?>[ports];
        offered=new int[ports]; held=new int[ports]; neutral=new boolean[ports];
        for(int p=0;p<ports;p++){pending[p]=new ArrayDeque<>();neutral[p]=true;}
    }
    void offer(int port,int mask){
        check(port);if((mask&~4095)!=0)throw new IllegalArgumentException("MD input bits");
        if(neutral[port]){if(mask==0)neutral[port]=false;return;}
        if(mask==offered[port])return;
        if(pending[port].size()>=128)throw new IllegalStateException("MD 输入队列超限");
        pending[port].addLast(mask);offered[port]=mask;
    }
    int[] next(){int[] result=new int[2];for(int p=0;p<ports;p++){if(!pending[p].isEmpty())held[p]=pending[p].removeFirst();result[p]=held[p];}return result;}
    void release(int port){check(port);pending[port].clear();offered[port]=held[port]=0;neutral[port]=true;}
    void clear(){for(int p=0;p<ports;p++)release(p);}
    private void check(int p){if(p<0||p>=ports)throw new IllegalArgumentException("MD input port");}
}
