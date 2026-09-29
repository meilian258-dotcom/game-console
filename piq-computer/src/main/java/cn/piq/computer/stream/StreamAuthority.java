package cn.piq.computer.stream;

import java.util.UUID;

/** Server-owned session capability. Controller and execution host are intentionally independent. */
public final class StreamAuthority {
    public final UUID host,session=UUID.randomUUID();
    private UUID controller;
    private boolean allowed;
    private long epoch;
    private long video=-1,audio=-1;
    private int nextPart;
    private int parts;private long micros;
    public StreamAuthority(UUID host,UUID controller){this.host=host;this.controller=controller;}
    public boolean mayControl(UUID player){return host.equals(player)||allowed;}
    public boolean setAllowed(UUID player,boolean value){if(!host.equals(player))return false;allowed=value;return true;}
    public boolean allowed(){return allowed;}
    public UUID controller(){return controller;}
    public long epoch(){return epoch;}
    public void controller(UUID player){controller=player;epoch++;}
    public boolean media(UUID player,StreamPart p){
        if(!host.equals(player)||!session.equals(p.session()))return false;
        if(p.kind()==1){if(p.sequence()<=audio)return false;audio=p.sequence();return true;}
        if(p.index()==0){if(p.sequence()<=video)return false;video=p.sequence();nextPart=0;parts=p.count();micros=p.micros();}
        if(p.sequence()!=video||p.index()!=nextPart||p.count()!=parts||p.micros()!=micros)return false;
        nextPart++;return true;
    }
}
