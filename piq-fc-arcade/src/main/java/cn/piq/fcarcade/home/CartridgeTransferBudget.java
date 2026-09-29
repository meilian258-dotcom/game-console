package cn.piq.fcarcade.home;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Global allocation gate; reservations survive async validation until its completion/cancellation. */
public final class CartridgeTransferBudget {
    private final Map<UUID, Integer> reservations = new HashMap<>();
    private long bytes;
    public synchronized boolean reserve(UUID owner, int amount) {
        if (owner == null || amount <= 0 || reservations.containsKey(owner)
                || reservations.size() >= CartridgeLimits.MAX_TRANSFERS
                || amount > CartridgeLimits.MAX_RESERVED_BYTES - bytes) return false;
        reservations.put(owner, amount);
        bytes += amount;
        return true;
    }
    public synchronized void release(UUID owner) {
        Integer amount = reservations.remove(owner);
        if (amount != null) bytes -= amount;
    }
    public synchronized long reservedBytes() { return bytes; }
    public synchronized int count() { return reservations.size(); }
}
