package cn.piq.fcarcade.home;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Inventory menus may split/copy stack objects. Only one physical token counts. */
public final class HomeControllerInventory {
    public enum Status { MISSING, UNIQUE, AMBIGUOUS }
    public record Selection<T>(Status status, T value) {}
    public enum TossAction { RECYCLE_COPY, RETURN_LOAN }
    private HomeControllerInventory() {}
    public static <T> Selection<T> locate(UUID id, List<T> slots, Function<T, UUID> token, ToIntFunction<T> count) {
        T found = null;
        var visited = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        for (T slot : slots) {
            if (!visited.add(slot)) continue; // Inventory and menu can expose the same physical stack.
            if (count.applyAsInt(slot) <= 0 || !id.equals(token.apply(slot))) continue;
            if (found != null || count.applyAsInt(slot) != 1) return new Selection<>(Status.AMBIGUOUS, null);
            found = slot;
        }
        return new Selection<>(found == null ? Status.MISSING : Status.UNIQUE, found);
    }
    /** Vanilla clears an outside-click cursor only after ItemTossEvent returns. */
    public static <T> boolean removedForToss(Selection<T> remaining, T cursor, T dropped) {
        return remaining.status() == Status.MISSING || (remaining.status() == Status.UNIQUE
                && remaining.value() == cursor && cursor == dropped);
    }
    public static boolean keepForPlayer(HomeControllerLedger.Lease lease, UUID player, long tick) {
        return lease != null && player.equals(lease.player()) && lease.phase() != HomeControllerLedger.Phase.IN_TRANSIT
                && (lease.phase() == HomeControllerLedger.Phase.ACTIVE || lease.phase() == HomeControllerLedger.Phase.IDLE || tick < lease.deadline());
    }
    public static boolean keepDropped(HomeControllerLedger.Lease lease, UUID entity, int count, long tick) {
        return lease != null && lease.phase() == HomeControllerLedger.Phase.IN_TRANSIT && count == 1
                && entity.equals(lease.carrier()) && tick < lease.deadline();
    }
    /** Shared by the real ItemTossEvent and the physical-count regression tests. */
    public static TossAction tossAction(HomeControllerLedger.Lease lease, UUID player, boolean alive,
                                       boolean removedSoleToken, long tick) {
        if (lease == null || !player.equals(lease.player())) return TossAction.RECYCLE_COPY;
        if (!alive || !keepForPlayer(lease, player, tick)) return TossAction.RETURN_LOAN;
        if (!removedSoleToken) return TossAction.RECYCLE_COPY;
        // Both wired ports belong to their console. Q/outside-inventory drop
        // means return, never a transferable world item (including idle P2).
        return TossAction.RETURN_LOAN;
    }
    /** Consume only explicitly selected loans; aliases never count as extra physical objects. */
    public static <T> int recycle(List<T> locations, Predicate<T> selected, ToIntFunction<T> count, Consumer<T> consume) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        int removed = 0;
        for (T item : locations) {
            if (!visited.add(item) || count.applyAsInt(item) <= 0 || !selected.test(item)) continue;
            removed += count.applyAsInt(item); consume.accept(item);
        }
        return removed;
    }
}
