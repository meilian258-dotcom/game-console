package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Public display identity only: deliberately contains no game, path, controller, or ROM authority. */
public record WatchDescriptor(ResourceLocation provider, UUID source, UUID hostLease,
        ResourceLocation dimension, WatchAnchor origin, UUID link, List<WatchAnchor> screens) {
    public WatchDescriptor {
        Objects.requireNonNull(provider); Objects.requireNonNull(source); Objects.requireNonNull(hostLease);
        Objects.requireNonNull(dimension); Objects.requireNonNull(origin); screens=List.copyOf(screens);
        if(provider.toString().length()>128||dimension.toString().length()>128)throw new IllegalArgumentException("watch identifier");
        if(screens.isEmpty()||screens.size()>2||screens.stream().distinct().count()!=screens.size())throw new IllegalArgumentException("watch screens");
    }
}
