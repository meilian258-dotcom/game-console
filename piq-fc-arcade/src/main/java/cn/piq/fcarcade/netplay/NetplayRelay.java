package cn.piq.fcarcade.netplay;

import java.util.*;
import java.util.function.BiConsumer;

/** Thread-safe capability gate. Game authority renews it; network threads only route bytes. */
public final class NetplayRelay<C> implements AutoCloseable {
    public record Delivery(NetplayChunk chunk,int port) { public Delivery(NetplayChunk chunk,boolean player){this(chunk,player?1:-1);} public boolean player(){return port>=0;} }
    public record Ticket(UUID id,int port) { public Ticket(UUID id,boolean player){this(id,player?1:-1);} public boolean player(){return port>=0;} }
    private final long session;
    private final C host;
    private final int capacity;
    private final UUID hostTicket=UUID.randomUUID();
    private final BiConsumer<C,Delivery> sender;
    private final Map<C,Peer<C>> peers=new IdentityHashMap<>();
    private final Map<UUID,Peer<C>> tickets=new HashMap<>();
    private boolean closed;
    private String lastRejection="";
    public synchronized String lastRejection(){return lastRejection;}
    private long expires=System.nanoTime()+1_000_000_000L;
    private static final class Peer<C> {
        final C connection;final Ticket ticket;boolean open;long up,down,window=System.nanoTime();int used;
        Peer(C c,int port){connection=c;ticket=new Ticket(UUID.randomUUID(),port);}
    }
    public NetplayRelay(long session,C host,BiConsumer<C,Delivery> sender){this(session,host,sender,2);}
    public NetplayRelay(long session,C host,BiConsumer<C,Delivery> sender,int capacity){
        if(capacity<1||capacity>4)throw new IllegalArgumentException("Capacity");
        this.session=session;this.host=Objects.requireNonNull(host);this.sender=Objects.requireNonNull(sender);this.capacity=capacity;
    }
    public synchronized Ticket grant(C connection,boolean player) {
        return grant(connection,connection==host?0:player?1:-1);
    }
    /** Fixed server-authorized port, never arrival order or a peer's requested device. */
    public synchronized Ticket grant(C connection,int port) {
        if(closed)throw new IllegalStateException("Closed Netplay room");
        if(port< -1||port>=capacity||connection==host&&port!=0||connection!=host&&port==0)throw new IllegalArgumentException("Port");
        if(connection==host)return new Ticket(hostTicket,0);
        var old=peers.get(connection);if(old!=null&&old.ticket.port()==port)return old.ticket;
        if(port>=0&&peers.values().stream().anyMatch(p->p!=old&&p.ticket.port()==port))return null;
        if(old!=null)revoke(old);
        if(peers.size()>=8)return null;
        var peer=new Peer<C>(connection,port);peers.put(connection,peer);tickets.put(peer.ticket.id(),peer);return peer.ticket;
    }
    public synchronized void renew(Set<C> current){
        if(closed)return;
        for(var peer:List.copyOf(peers.values()))if(!current.contains(peer.connection))revoke(peer);
        expires=System.nanoTime()+1_000_000_000L;
    }
    /** Addon observation reserves a peer slot for P2 even when no player has joined yet. */
    public synchronized Ticket grantObserver(C connection){
        var old=peers.get(connection);
        if(old!=null&&!old.ticket.player())return old.ticket;
        if(peers.values().stream().filter(p->!p.ticket.player()).count()>=9-capacity)return null;
        return grant(connection,false);
    }
    public synchronized void receive(C source,NetplayChunk chunk) {
        if(closed||chunk.session()!=session)return;
        if(source==host&&chunk.ticket().equals(hostTicket)&&chunk.kind()==NetplayChunk.CLOSE){close();return;}
        var peer=tickets.get(chunk.ticket());if(peer==null||source!=host&&source!=peer.connection)return;
        if(chunk.kind()==NetplayChunk.CLOSE){revoke(peer);return;}
        if(System.nanoTime()>expires){lastRejection="Authority renewal expired";revoke(peer);return;}
        long now=System.nanoTime();if(now-peer.window>=1_000_000_000L){peer.window=now;peer.used=0;}
        peer.used+=chunk.bytes().length+40;
        if(peer.used>2*1024*1024){lastRejection="Peer rate limit: "+peer.used;revoke(peer);return;}
        if(chunk.kind()==NetplayChunk.OPEN){
            if(source==host||peer.open)return;peer.open=true;sender.accept(host,new Delivery(chunk,peer.ticket.port()));return;
        }
        if(!peer.open)return;
        if(chunk.kind()==NetplayChunk.ACK){if(source==host)sender.accept(peer.connection,new Delivery(chunk,peer.ticket.port()));return;}
        if(chunk.kind()==NetplayChunk.DATA){
            if(source==host?chunk.sequence()!=peer.down++:chunk.sequence()!=peer.up++){lastRejection="Out of order stream";revoke(peer);return;}
            sender.accept(source==host?peer.connection:host,new Delivery(chunk,peer.ticket.port()));
        }
    }
    private void revoke(Peer<C> peer){
        peers.remove(peer.connection);tickets.remove(peer.ticket.id());
        var close=new Delivery(new NetplayChunk(session,peer.ticket.id(),NetplayChunk.CLOSE,0,new byte[0]),-1);
        sender.accept(host,close);sender.accept(peer.connection,close);
    }
    public synchronized void revoke(C connection){var p=peers.get(connection);if(p!=null)revoke(p);}
    /** Revoke only this generation; a retired observer must not evict its new player ticket. */
    public synchronized void revoke(C connection,UUID ticket){var p=peers.get(connection);if(p!=null&&p.ticket.id().equals(ticket))revoke(p);}
    public synchronized boolean closed(){return closed;}
    @Override public synchronized void close(){if(closed)return;closed=true;for(var p:List.copyOf(peers.values()))revoke(p);}
}
