package cn.piq.fcarcade.client;

import java.util.*;
import java.util.function.*;

/** Local keyboard capture proof, deliberately not a server input authorization. */
public final class ControllerCapturePolicy {
    /** Stable across ItemStack copies, different when the actual device or held hand changes. */
    public record Held(UUID lease,int port,int hand,boolean gun) {}
    private ControllerCapturePolicy() {}
    public static boolean receipt(UUID player, UUID lease, int port, UUID visualPlayer, UUID visualLease,
                                  boolean docked, double distanceSquared) {
        return player != null && lease != null && port >= 0 && port < 2 && !docked
                && player.equals(visualPlayer) && lease.equals(visualLease)
                && Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 36;
    }
    /** Alias slots count once; any second physical copy rejects even if only one is held. */
    public static <T> boolean uniqueHeld(T held, UUID lease, Iterable<T> items,
                                         Function<T,UUID> token, ToIntFunction<T> count) {
        if (held == null || lease == null || count.applyAsInt(held) != 1) return false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<T,Boolean>());
        T found = null;
        for (T item : items) {
            if (item == null || !seen.add(item) || count.applyAsInt(item) <= 0 || !lease.equals(token.apply(item))) continue;
            if (found != null || count.applyAsInt(item) != 1) return false;
            found = item;
        }
        return found == held;
    }
}
