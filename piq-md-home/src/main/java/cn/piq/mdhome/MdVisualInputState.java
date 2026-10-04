// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

/** Server-owned, per-lease visual coalescing. Never feeds the game input path. */
final class MdVisualInputState {
    record Sample(long sequence,int mask,int pressedMask,boolean reset){}
    private int held,pressed;
    private boolean dirty=true,cancelled;
    private long last=Long.MIN_VALUE,sequence;
    void offer(int mask){
        if((mask&~4095)!=0)throw new IllegalArgumentException("MD visual bits");
        pressed|=mask&~held;dirty|=held!=mask;held=mask;cancelled=false;
    }
    Sample poll(long tick){
        if(last!=Long.MIN_VALUE&&(tick-last<2||!dirty&&tick-last<10))return null;
        return emit(tick,false);
    }
    Sample clear(long tick){held=pressed=0;dirty=true;cancelled=true;return emit(tick,true);}
    Sample cancel(long tick){return !cancelled?clear(tick):null;}
    private Sample emit(long tick,boolean reset){last=tick;dirty=false;var result=new Sample(++sequence,held,pressed,reset);pressed=0;return result;}
    static boolean recipient(boolean connected,boolean alive,boolean sameDimension,boolean visible,double squared){
        return connected&&alive&&sameDimension&&visible&&Double.isFinite(squared)&&squared>=0&&squared<=32*32;
    }
}
