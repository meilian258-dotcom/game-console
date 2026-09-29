package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Identity-bearing display anchor; a position alone never authorizes observation. */
public record WatchAnchor(BlockPos pos, UUID identity) {
    public WatchAnchor { pos=Objects.requireNonNull(pos).immutable(); Objects.requireNonNull(identity); }
}
