package cn.piq.fcarcade.cabinet;

import net.minecraft.resources.ResourceLocation;
import cn.piq.retro.api.RetroBackendRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

/** Common-side declarations only. Addons own their cores; FC owns physical cabinet authority. */
public final class CabinetBackends {
    public static final int MAX_BACKENDS = RetroBackendRegistry.MAX_BACKENDS;
    public static final ResourceLocation NES = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "nes");
    private static final RetroBackendRegistry ENTRIES = new RetroBackendRegistry();
    private static final Map<ResourceLocation,Integer> NETWORK_PLAYERS = new HashMap<>();
    private static final Map<ResourceLocation,CabinetSyncPolicy> SYNCHRONOUS = new HashMap<>();
    static { register(NES, "FC / NES", false); }
    private CabinetBackends() {}

    public record Entry(ResourceLocation id, String displayName, boolean localOnly) {
        public Entry {
            Objects.requireNonNull(id, "backend id");
            if (id.toString().length() > 128 || displayName == null || displayName.isBlank()
                    || displayName.length() > 64 || displayName.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid cabinet backend declaration");
        }
    }

    public static synchronized void register(ResourceLocation id, String displayName, boolean localOnly) {
        Entry entry = new Entry(id, displayName, localOnly);
        ENTRIES.register(entry.id().toString(), entry.displayName(), entry.localOnly());
    }

    public static synchronized List<Entry> entries() { return ENTRIES.entries().stream().map(CabinetBackends::legacyEntry).toList(); }
    /** Explicit opt-in: the client adapter must implement independent port release. */
    public static synchronized void registerNetwork(ResourceLocation id, int maxPlayers) {
        Entry entry = find(id);
        if (entry == null || entry.localOnly() || id.equals(NES) || maxPlayers < 1 || maxPlayers > 4
                || NETWORK_PLAYERS.containsKey(id)) throw new IllegalArgumentException("Invalid network cabinet capability");
        NETWORK_PLAYERS.put(id, maxPlayers);
    }
    public static synchronized int maxPlayers(ResourceLocation id) { return NETWORK_PLAYERS.getOrDefault(id, 0); }
    /** Explicit opt-in after real state roundtrip and multi-instance determinism verification. */
    public static synchronized void registerSync(ResourceLocation id) {
        registerSyncPolicy(id,new CabinetSyncPolicy(maxPlayers(id),null));
    }
    /** Explicit fixed-profile cold-snapshot opt-in. This never changes media-mode capacity. */
    public static synchronized void registerSnapshotSync(ResourceLocation id,int maxPlayers,String compatibilityId) {
        Objects.requireNonNull(compatibilityId,"Fixed snapshot compatibility is required");
        registerSyncPolicy(id,new CabinetSyncPolicy(maxPlayers,compatibilityId));
    }
    private static void registerSyncPolicy(ResourceLocation id,CabinetSyncPolicy policy) {
        if(id==null||id.equals(NES)||maxPlayers(id)<policy.maxPlayers()||SYNCHRONOUS.containsKey(id))
            throw new IllegalArgumentException("Invalid synchronous capability");
        SYNCHRONOUS.put(id,policy);
    }
    public static synchronized boolean supportsSync(ResourceLocation id) { return SYNCHRONOUS.containsKey(id); }
    public static synchronized int syncMaxPlayers(ResourceLocation id) { var policy=SYNCHRONOUS.get(id);return policy==null?0:policy.maxPlayers(); }
    public static synchronized boolean hostSnapshotSync(ResourceLocation id) { var policy=SYNCHRONOUS.get(id);return policy!=null&&policy.hostSnapshot(); }
    public static synchronized String expectedSyncCompatibility(ResourceLocation id) { var policy=SYNCHRONOUS.get(id);return policy==null?null:policy.expectedCompatibility(); }
    static synchronized CabinetSyncPolicy syncPolicy(ResourceLocation id) { return SYNCHRONOUS.get(id); }
    public static synchronized Entry find(ResourceLocation id) {
        var entry = id == null ? null : ENTRIES.find(id.toString());
        return entry == null ? null : legacyEntry(entry);
    }
    private static Entry legacyEntry(RetroBackendRegistry.Descriptor entry) {
        return new Entry(ResourceLocation.parse(entry.id()), entry.displayName(), entry.localOnly());
    }
}
