package cn.piq.fcarcade.cabinet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Common-only provider registry. No client class or reverse add-on dependency. */
public final class WatchProviders {
    private static final Map<ResourceLocation,WatchProvider> PROVIDERS=new LinkedHashMap<>();
    private WatchProviders() {}
    public static synchronized void register(ResourceLocation id,WatchProvider provider) {
        Objects.requireNonNull(id); Objects.requireNonNull(provider);
        if(PROVIDERS.containsKey(id))throw new IllegalArgumentException("duplicate watch provider: "+id);
        if(PROVIDERS.size()>=8)throw new IllegalStateException("watch provider limit");
        PROVIDERS.put(id,provider);
    }
    public static synchronized WatchProvider find(ResourceLocation id) { return PROVIDERS.get(id); }
    public static synchronized Map<ResourceLocation,WatchProvider> entries() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(PROVIDERS)); }
}
