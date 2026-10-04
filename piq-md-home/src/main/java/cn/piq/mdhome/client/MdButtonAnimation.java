// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import java.util.Arrays;
import java.util.Objects;

/** Render-thread smoother in canonical SFC-layout bits, before MdProfile's core remapping. */
public final class MdButtonAnimation {
    private final double[] values=new double[12];
    private Object identity;
    private long time;
    void update(Object key,int mask,long now){
        if(key==null||(mask&~4095)!=0){clear();return;}
        double elapsed;
        if(!Objects.equals(identity,key)){clear();identity=key;elapsed=1.0/60;}
        else elapsed=Math.max(0,Math.min(.1,(now-time)/1e9));
        time=now;
        for(int bit=0;bit<12;bit++){
            boolean down=(mask&(1<<bit))!=0;
            values[bit]=down?Math.min(1,values[bit]+elapsed/.025):Math.max(0,values[bit]-elapsed/.070);
        }
    }
    public double value(int mask){if(Integer.bitCount(mask)!=1||(mask&~4095)!=0)return 0;return values[Integer.numberOfTrailingZeros(mask)];}
    boolean matches(Object key){return identity!=null&&identity.equals(key);}
    void clear(){identity=null;time=0;Arrays.fill(values,0);}
}
