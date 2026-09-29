package cn.piq.fcarcade.client.cabinet;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One consent prompt, bound to one exact connection and host lease. No seat authority. */
final class CabinetPromptGuard {
    static final long TIMEOUT_NANOS=30_000_000_000L;
    private Object connection;
    private UUID room,host,active;
    private long began;
    private final Set<UUID> seen=new HashSet<>();
    boolean claim(Object c,UUID r,UUID h,UUID token,long now){
        if(c==null||r==null||h==null||token==null)return false;
        if(!scope(c,r,h)){clear();connection=c;room=r;host=h;}
        if(active!=null||seen.size()>=256||!seen.add(token))return false;
        active=token;began=now;return true;
    }
    boolean live(Object c,UUID r,UUID h,UUID token,long now){
        return scope(c,r,h)&&active!=null&&active.equals(token)&&now-began>=0&&now-began<TIMEOUT_NANOS;
    }
    boolean finish(Object c,UUID r,UUID h,UUID token){
        if(!scope(c,r,h)||active==null||!active.equals(token))return false;
        active=null;return true;
    }
    private boolean scope(Object c,UUID r,UUID h){return c!=null&&connection==c&&room.equals(r)&&host.equals(h);}
    void clear(){connection=null;room=host=active=null;began=0;seen.clear();}
}
