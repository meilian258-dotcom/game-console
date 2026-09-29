package cn.piq.sfchome.client;

import java.util.Objects;

/** Per-connection demand ordering. A closed publisher cannot be revived by a heartbeat. */
final class SfcWatchDemand {
    private Object connection,identity;
    private long revision=-1;
    private boolean enabled,blocked;

    void connection(Object value) {
        if(connection!=value){connection=value;identity=null;revision=-1;enabled=false;blocked=false;}
    }
    boolean accept(Object source,long sequence,Object key,boolean needed) {
        if(source==null||source!=connection||sequence<0||key==null||sequence<revision)return false;
        // A same-revision zero is the shared client's local timeout, not a new server stop.
        if(sequence==revision)return !blocked&&enabled&&needed&&Objects.equals(identity,key);
        revision=sequence;identity=key;enabled=needed;blocked=!needed;
        return needed;
    }
    boolean acceptAuthorized(Object source,long sequence,Object key,boolean needed,boolean authorized){
        return authorized&&accept(source,sequence,key,needed);
    }
    long revision(){return revision;}
    boolean blocked(){return blocked;}
    boolean matches(long sequence,Object key){return sequence==revision&&Objects.equals(identity,key);}
    void block(){blocked=true;}
}
