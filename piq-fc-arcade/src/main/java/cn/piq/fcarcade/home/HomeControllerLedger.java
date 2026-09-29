package cn.piq.fcarcade.home;

import java.util.*;

/** Runtime-only authority: restarting deliberately invalidates all old physical tokens. */
public final class HomeControllerLedger {
    public record Console(UUID id, String dimension, int x, int y, int z) {}
    public enum Phase { ACTIVE, IN_TRANSIT, AWAITING_APPROVAL, IDLE }
    public record Lease(UUID id, Console console, long session, int port, UUID player,
                        Phase phase, UUID carrier, long deadline) {}
    private final Map<UUID, Lease> leases = new HashMap<>();

    public Lease get(UUID id) { return id == null ? null : leases.get(id); }
    public List<Lease> snapshots() { return List.copyOf(leases.values()); }
    public Lease player(UUID player) {
        return leases.values().stream().filter(value -> player.equals(value.player())).findFirst().orElse(null);
    }
    public Lease port(long session, int port) {
        return leases.values().stream().filter(value -> value.session() == session && value.port() == port).findFirst().orElse(null);
    }
    public Lease issue(Console console, long session, int port, UUID player) {
        if (console == null || console.id() == null || player == null || session <= 0 || port < 0 || port > 1
                || player(player) != null || port(session, port) != null || socket(console,port)!=null) return null;
        var lease = new Lease(UUID.randomUUID(), console, session, port, player, Phase.ACTIVE, null, 0);
        leases.put(lease.id(), lease); return lease;
    }
    public Lease socket(Console console,int port){return leases.values().stream().filter(l->l.console().equals(console)&&l.port()==port).findFirst().orElse(null);}
    /** Physical possession without an emulator, epoch or input authority. */
    public Lease borrow(Console console,int port,UUID player){
        if(console==null||console.id()==null||player==null||port<0||port>1||player(player)!=null||socket(console,port)!=null)return null;
        var lease=new Lease(UUID.randomUUID(),console,0,port,player,Phase.IDLE,null,0);leases.put(lease.id(),lease);return lease;
    }
    public Lease idle(UUID id){var old=get(id);if(old==null||old.phase()==Phase.IN_TRANSIT)return null;
        var next=new Lease(id,old.console(),0,old.port(),old.player(),Phase.IDLE,null,0);leases.put(id,next);return next;}
    public Lease toss(UUID id, UUID player, UUID entity, long deadline) {
        var old = get(id);
        if (old == null || old.port() != 1 || !player.equals(old.player()) || entity == null
                || old.phase() == Phase.IN_TRANSIT) return null;
        var next = new Lease(id, old.console(), old.session(), old.port(), null, Phase.IN_TRANSIT, entity, deadline);
        leases.put(id, next); return next;
    }
    public Lease pickup(UUID id, UUID entity, UUID player, long deadline) {
        var old = get(id);
        if (old == null || old.phase() != Phase.IN_TRANSIT || !entity.equals(old.carrier()) || player(player) != null) return null;
        // Rotate the token only for a receipt from the actual tossed ItemEntity.
        leases.remove(id);
        var next = new Lease(UUID.randomUUID(), old.console(), old.session(), old.port(), player,
                old.session()==0?Phase.IDLE:Phase.AWAITING_APPROVAL, null, old.session()==0?0:deadline);
        leases.put(next.id(), next); return next;
    }
    public boolean activate(UUID id, UUID player, Console console, long session, int port) {
        var old = get(id);
        if (old == null || !player.equals(old.player()) || !console.equals(old.console())
                || session<=0 || (old.phase()!=Phase.IDLE&&session != old.session()) || port != old.port()
                || (old.phase()!=Phase.IDLE&&old.phase() != Phase.AWAITING_APPROVAL)) return false;
        leases.put(id, new Lease(id, console, session, port, player, Phase.ACTIVE, null, 0)); return true;
    }
    public boolean authorized(UUID id, UUID player, Console console, long session, int port) {
        var old = get(id);
        return old != null && old.phase() == Phase.ACTIVE && player.equals(old.player())
                && console.equals(old.console()) && session == old.session() && port == old.port();
    }
    public Lease revoke(UUID id) { return leases.remove(id); }
    public List<Lease> revokeSession(long session) {
        var old = snapshots().stream().filter(lease -> lease.session() == session).toList();
        old.forEach(lease -> leases.remove(lease.id())); return old;
    }
}
