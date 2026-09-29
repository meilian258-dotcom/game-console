package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Pure exact-connection/member gate; server world/protection checks must additionally succeed. */
public final class CabinetSyncGate {
    private final UUID room,member;private final Object connection;private final boolean host;
    private boolean closed,active;private UUID pending;private long goal,expires;
    private final Set<UUID> retired=new HashSet<>();
    public CabinetSyncGate(UUID room,UUID member,Object connection,boolean host){this.room=Objects.requireNonNull(room);this.member=Objects.requireNonNull(member);this.connection=Objects.requireNonNull(connection);this.host=host;}
    public boolean accepts(UUID room,UUID member,int epoch,Object source){return !closed&&epoch==1&&this.room.equals(room)&&this.member.equals(member)&&connection==source;}
    public boolean input(UUID room,UUID member,int epoch,Object source){return active&&accepts(room,member,epoch,source);}
    public boolean active(){return !closed&&active;}
    public void activateHost(){if(!closed&&host)active=true;}
    public boolean begin(UUID token,long goal,long expires){
        if(closed||host||pending!=null||token==null||retired.contains(token)||retired.size()>=32||goal<0||expires<1)return false;
        this.pending=token;this.goal=goal;this.expires=expires;active=false;return true;
    }
    public boolean acknowledge(UUID token,long frame,long sentThrough,boolean complete,long now){
        if(closed||host||pending==null||!pending.equals(token)||!complete||frame<goal||frame>sentThrough||now<0||now>=expires)return false;
        retired.add(pending);pending=null;active=true;return true;
    }
    public void close(){closed=true;active=false;pending=null;retired.clear();}
}
