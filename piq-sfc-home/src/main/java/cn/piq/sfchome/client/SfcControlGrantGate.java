package cn.piq.sfchome.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A retired physical loan must never re-arm the still-running host through a late packet. */
public final class SfcControlGrantGate {
    public static final int LIMIT=256;
    private Object connection;
    private long session;
    private int epoch;
    private final Set<UUID> retired=new HashSet<>();
    public void bind(Object c,long s,int e){
        if(c==null||s<=0||e<=0)throw new IllegalArgumentException("Invalid runtime scope");
        if(connection!=c||session!=s||epoch!=e){clear();connection=c;session=s;epoch=e;}
    }
    public boolean current(Object c,long s,int e){return c!=null&&connection==c&&session==s&&epoch==e;}
    public boolean mayGrant(Object c,long s,int e,UUID lease){return current(c,s,e)&&lease!=null&&retired.size()<LIMIT&&!retired.contains(lease);}
    public boolean retire(Object c,long s,int e,UUID lease){
        if(!current(c,s,e)||lease==null)return false;
        if(retired.size()<LIMIT)retired.add(lease);return true;
    }
    public int retiredCount(){return retired.size();}
    public void clear(){connection=null;session=0;epoch=0;retired.clear();}
}
