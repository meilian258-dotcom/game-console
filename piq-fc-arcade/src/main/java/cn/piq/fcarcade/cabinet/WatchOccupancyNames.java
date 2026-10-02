package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Bounded presentation of real operator identities supplied by a trusted provider. */
public final class WatchOccupancyNames {
    private WatchOccupancyNames() {}
    public static String format(List<UUID> operators, Function<UUID, String> onlineName) {
        if (operators == null) return "";
        return operators.stream().limit(4).filter(java.util.Objects::nonNull).distinct()
                .map(onlineName).filter(name -> name != null && !name.isBlank())
                .map(name -> name.length() > 32 ? name.substring(0, 32) : name)
                .collect(java.util.stream.Collectors.joining("、"));
    }
}
