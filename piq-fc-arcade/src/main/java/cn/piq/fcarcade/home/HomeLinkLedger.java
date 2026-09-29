package cn.piq.fcarcade.home;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread transaction state, independent of Minecraft and loaded chunks. */
final class HomeLinkLedger {
    enum Kind { CONSOLE, TV }
    record Endpoint(UUID id, String dimension, int x, int y, int z, Kind kind) {}
    record Link(UUID id, Endpoint console, Endpoint tv, boolean closed,
                boolean refundClaimed, boolean consoleCleared, boolean tvCleared) {
        boolean owns(Endpoint endpoint) { return console.equals(endpoint) || tv.equals(endpoint); }
        Endpoint other(Endpoint endpoint) {
            if (console.equals(endpoint)) return tv;
            if (tv.equals(endpoint)) return console;
            return null;
        }
    }
    record CloseResult(Link link, boolean refundCable) {}

    private final Map<UUID, Link> links = new HashMap<>();
    private final Map<UUID, UUID> activeEndpoints = new HashMap<>();

    static boolean inRange(Endpoint console, Endpoint tv) {
        double dx = (double) console.x - tv.x, dy = (double) console.y - tv.y;
        double dz = (double) console.z - tv.z;
        return console.kind == Kind.CONSOLE && tv.kind == Kind.TV
                && !console.id.equals(tv.id) && console.dimension.equals(tv.dimension)
                && dx * dx + dy * dy + dz * dz <= 64.0;
    }

    Link connect(Endpoint console, Endpoint tv) {
        if (!inRange(console, tv) || activeEndpoints.containsKey(console.id)
                || activeEndpoints.containsKey(tv.id)) return null;
        Link link = new Link(UUID.randomUUID(), console, tv, false, false, false, false);
        links.put(link.id, link);
        activeEndpoints.put(console.id, link.id);
        activeEndpoints.put(tv.id, link.id);
        return link;
    }

    Link get(UUID id) { return id == null ? null : links.get(id); }

    Link activeFor(Endpoint endpoint) {
        Link link = get(activeEndpoints.get(endpoint.id()));
        return link != null && !link.closed() && link.owns(endpoint) ? link : null;
    }

    CloseResult close(UUID id, Endpoint requester) {
        Link old = get(id);
        if (old == null || !old.owns(requester)) return null;
        boolean refund = !old.refundClaimed;
        Link closed = new Link(id, old.console, old.tv, true, true,
                old.consoleCleared, old.tvCleared);
        links.put(id, closed);
        activeEndpoints.remove(old.console.id, id);
        activeEndpoints.remove(old.tv.id, id);
        return new CloseResult(closed, refund);
    }

    boolean acknowledgeCleared(UUID id, Endpoint endpoint) {
        Link old = get(id);
        if (old == null || !old.closed || !old.owns(endpoint)) return false;
        boolean console = old.consoleCleared || old.console.equals(endpoint);
        boolean tv = old.tvCleared || old.tv.equals(endpoint);
        if (console && tv) links.remove(id);
        else links.put(id, new Link(id, old.console, old.tv, true, old.refundClaimed, console, tv));
        return true;
    }

    Collection<Link> snapshots() { return List.copyOf(links.values()); }

    boolean restore(Link link) {
        if (link == null || !inRange(link.console, link.tv) || links.containsKey(link.id)
                || (!link.closed && (activeEndpoints.containsKey(link.console.id)
                || activeEndpoints.containsKey(link.tv.id)))) return false;
        if (link.closed && link.consoleCleared && link.tvCleared) return true;
        links.put(link.id, link);
        if (!link.closed) {
            activeEndpoints.put(link.console.id, link.id);
            activeEndpoints.put(link.tv.id, link.id);
        }
        return true;
    }
}
