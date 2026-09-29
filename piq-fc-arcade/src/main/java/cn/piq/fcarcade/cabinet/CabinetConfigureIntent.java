package cn.piq.fcarcade.cabinet;

import java.util.*;

/** One client-menu attempt; not server authorization. Bind to connection identity and exact target. */
public final class CabinetConfigureIntent<T> {
    private final LinkedHashSet<UUID> seen=new LinkedHashSet<>();
    private Object connection;private T target;private String backend;private long expires;
    public boolean arm(Object connection,T target,String backend,UUID token,long now,long lifetime){
        clear();
        if(connection==null||target==null||backend==null||token==null||lifetime<1||seen.contains(token))return false;
        if(seen.size()>=64)seen.remove(seen.iterator().next());seen.add(token);
        this.connection=connection;this.target=target;this.backend=backend;this.expires=now+lifetime;return true;
    }
    public boolean consume(Object connection,T target,String backend,long now){
        boolean matches=this.connection==connection&&connection!=null&&Objects.equals(this.target,target)
                &&Objects.equals(this.backend,backend)&&now-expires<0;
        clear();return matches;
    }
    public void expire(Object currentConnection,long now){if(connection!=currentConnection||now-expires>=0)clear();}
    public void clear(){connection=null;target=null;backend=null;expires=0;}
}
