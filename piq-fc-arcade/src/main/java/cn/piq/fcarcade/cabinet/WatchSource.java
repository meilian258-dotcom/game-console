package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;

/** Server-only source owner identity, separate from a viewer lease. */
public record WatchSource(WatchDescriptor descriptor, UUID hostPlayer) {
    public WatchSource { Objects.requireNonNull(descriptor); Objects.requireNonNull(hostPlayer); }
}
