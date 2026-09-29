// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;
import java.util.*;
/** Bounded cross-thread input mailbox. The native worker consumes complete snapshots. */
final class PvzJniInputs {
    record State(int pad,int x,int y,int buttons,boolean pointer,int[] keys){}
    private final List<int[]> keys=new ArrayList<>();private final Set<Integer> held=new HashSet<>();
    private int pad,x,y,buttons;private boolean pointer;
    synchronized void input(int p,int a,int b,int btn,boolean point){pad=p&65535;x=Math.clamp(a,-32768,32767);y=Math.clamp(b,-32768,32767);buttons=btn&3;pointer=point;}
    synchronized boolean key(boolean down,int key,int character){
        if(key<0||key>511||character<0||character>0x10ffff||character>=0xd800&&character<=0xdfff||keys.size()>=128)return false;
        if(down&&key>0&&!held.contains(key)&&held.size()>=128)return false;
        keys.add(new int[]{down?1:0,key,character});if(key>0){if(down)held.add(key);else held.remove(key);}return true;
    }
    synchronized void release(){pad=buttons=0;pointer=false;keys.clear();for(int k:held)keys.add(new int[]{0,k,0});held.clear();}
    synchronized State drain(){int[] k=new int[keys.size()*3];int i=0;for(var event:keys)for(int v:event)k[i++]=v;keys.clear();return new State(pad,x,y,buttons,pointer,k);}
}
