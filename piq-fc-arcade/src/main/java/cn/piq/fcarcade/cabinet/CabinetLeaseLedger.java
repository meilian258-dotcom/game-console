package cn.piq.fcarcade.cabinet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-thread-only, bounded ownership ledger. Generic key keeps lifecycle tests game-independent. */
final class CabinetLeaseLedger<K> {
    static final int MAX_LEASES = 64;
    static final long TIMEOUT_TICKS = 80;
    record Lease<K>(UUID id, UUID owner, K target, String backend, long expires) {}
    private final Map<UUID, Lease<K>> leases = new HashMap<>();

    Lease<K> acquire(UUID owner, K target, String backend, long now) {
        Objects.requireNonNull(owner); Objects.requireNonNull(target); Objects.requireNonNull(backend);
        if (leases.size() >= MAX_LEASES || leases.values().stream()
                .anyMatch(l -> l.owner().equals(owner) || l.target().equals(target))) return null;
        Lease<K> lease = new Lease<>(UUID.randomUUID(), owner, target, backend, now + TIMEOUT_TICKS);
        leases.put(lease.id(), lease);
        return lease;
    }
    Lease<K> get(UUID id) { return leases.get(id); }
    Lease<K> owner(UUID owner) { return leases.values().stream().filter(l -> l.owner().equals(owner)).findFirst().orElse(null); }
    Lease<K> target(K target) { return leases.values().stream().filter(l -> l.target().equals(target)).findFirst().orElse(null); }
    List<Lease<K>> all() { return List.copyOf(leases.values()); }
    boolean heartbeat(UUID owner, UUID id, long now) {
        var lease = leases.get(id);
        if (lease == null || !lease.owner().equals(owner) || now >= lease.expires()) return false;
        leases.put(id, new Lease<>(id, owner, lease.target(), lease.backend(), now + TIMEOUT_TICKS));
        return true;
    }
    Lease<K> release(UUID owner, UUID id) {
        var lease = leases.get(id);
        if (lease == null || !lease.owner().equals(owner)) return null;
        leases.remove(id);
        return lease;
    }
}
