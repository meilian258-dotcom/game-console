package cn.piq.mdhome;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Read-only snapshot of the two granted ports, not the host or borrowed inventory. */
final class MdOccupancy {
    private MdOccupancy() {}
    static <T> List<UUID> players(boolean active, T[] seats, BiPredicate<Integer,T> authorized,
                                 Function<T,UUID> identity) {
        if (!active) return List.of();
        var result = new ArrayList<UUID>(2);
        for (int port = 0; port < Math.min(2, seats.length); port++) {
            var seat = seats[port];
            if (seat == null || !authorized.test(port, seat)) continue;
            var player = identity.apply(seat);
            if (player != null && !result.contains(player)) result.add(player);
        }
        return List.copyOf(result);
    }
}
