package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;

/** Pure, bounded consent state. A pending applicant is not a room member or an input owner. */
final class CabinetJoinGate {
    static final int PROMPT_TICKS = 600, REQUEST_COOLDOWN = 20;
    record Offer(UUID token, long expires) {}
    record Pending(UUID token, UUID applicant, int port, long expires) {}
    private final UUID host, hostMember;
    private boolean offered, enabled;
    private Offer offer;
    private Pending pending;
    private long nextRequest;
    private boolean allowFirstPort;

    CabinetJoinGate(UUID host, UUID hostMember) {
        this.host = Objects.requireNonNull(host); this.hostMember = Objects.requireNonNull(hostMember);
    }
    CabinetJoinGate(UUID host,UUID hostMember,boolean allowFirstPort){this(host,hostMember);this.allowFirstPort=allowFirstPort;}
    boolean enabled() { return enabled; }
    Pending pending() { return pending; }
    boolean canAnswer(UUID token, long now) { return offer != null && offer.token().equals(token) && now >= 0 && now < offer.expires(); }
    Offer offer(long now) {
        if (offered || now < 0) return null;
        offered = true;
        return offer = new Offer(UUID.randomUUID(), now + PROMPT_TICKS);
    }
    /** Exactly one answer to the current host's initial offer. Replays cannot change that choice. */
    boolean allow(UUID player, UUID member, UUID token, boolean value, long now) {
        if (!host.equals(player) || !hostMember.equals(member) || offer == null
                || !offer.token().equals(token) || now < 0 || now >= offer.expires()) return false;
        offer = null; enabled = value; return true;
    }
    UUID expireOffer(long now) {
        if (offer == null || now < offer.expires()) return null;
        UUID token = offer.token(); offer = null; return token;
    }
    Pending request(UUID player, int port, long now) {
        Objects.requireNonNull(player);
        if (!enabled || pending != null || host.equals(player) || port < (allowFirstPort?0:1) || port > 3
                || now < 0 || now < nextRequest) return null;
        nextRequest = now + REQUEST_COOLDOWN;
        return pending = new Pending(UUID.randomUUID(), player, port, now + PROMPT_TICKS);
    }
    /** Take first, then revalidate physical authority and allocate the exact seat externally. */
    Pending decide(UUID player, UUID member, UUID token, long now) {
        if (!host.equals(player) || !hostMember.equals(member) || pending == null
                || !pending.token().equals(token) || now < 0 || now >= pending.expires()) return null;
        Pending result = pending; pending = null; return result;
    }
    Pending cancel(UUID player) {
        if (pending == null || !pending.applicant().equals(player)) return null;
        return clear();
    }
    Pending expire(long now) { return pending != null && now >= pending.expires() ? clear() : null; }
    Pending clear() { Pending result = pending; pending = null; return result; }
}
