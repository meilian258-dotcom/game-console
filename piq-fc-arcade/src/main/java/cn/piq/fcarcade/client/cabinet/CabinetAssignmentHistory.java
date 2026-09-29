package cn.piq.fcarcade.client.cabinet;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Connection-scoped replay guard; never evicts old leases and silently reauthorizes them. */
public final class CabinetAssignmentHistory {
    private Object connection;
    private final Set<UUID> seen=new HashSet<>();
    public void connection(Object current){if(connection!=current){connection=current;seen.clear();}}
    public boolean accept(Object current,UUID member){
        connection(current);
        return current!=null&&member!=null&&seen.size()<512&&seen.add(member);
    }
    public boolean seen(Object current,UUID member){return current!=null&&connection==current&&seen.contains(member);}
}
