package cn.piq.fcarcade.server;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.rom.NesCompatibility;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomSaveMode;
import cn.piq.fcarcade.rom.RomTransferLimits;
import cn.piq.fcarcade.session.ArcadeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.nio.file.LinkOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.LinkedHashMap;

final class ServerRomLibrary {
    private static final String SELECTION_FILE = "arcade-selections.properties";
    private static final String ROM_METADATA_FILE = "rom-metadata.properties";
    private static final String LEADERBOARD_FILE = "arcade-leaderboards.properties";
    static final long MAX_INDEXED_BYTES = 128L * 1024 * 1024;
    private static final ExecutorService INDEXER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PIQ-FC-ROM-index");
        thread.setDaemon(true);
        return thread;
    });

    private final RomRepository repository;
    private final Path selectionPath;
    private final Path metadataPath;
    private final Path leaderboardPath;
    private final Properties selections = new Properties();
    private final Properties metadata = new Properties();
    private final Properties leaderboards = new Properties();
    private final Map<String, RomDescriptor> cache = new HashMap<>();
    private final Executor indexExecutor;
    private final Runnable beforeIndexPublish;
    private final Consumer<String> indexWarning;
    // Null descriptor is a tombstone. This journal exists only for the active scan.
    private Map<String, RomDescriptor> indexChanges;
    private CompletableFuture<Void> indexing;
    private long nextRefreshNanos;
    private boolean capacityWarningReported;

    ServerRomLibrary(Path directory) {
        this(directory, INDEXER, () -> {}, message -> FcArcadeMod.LOGGER.warn(message));
    }

    /** Scheduling and publish hooks keep concurrent-index tests deterministic without Minecraft. */
    ServerRomLibrary(Path directory, Executor indexExecutor,
                     Runnable beforeIndexPublish, Consumer<String> indexWarning) {
        this.indexExecutor = indexExecutor;
        this.beforeIndexPublish = beforeIndexPublish;
        this.indexWarning = indexWarning;
        Path root = directory.toAbsolutePath().normalize();
        repository = new RomRepository(root);
        selectionPath = root.resolve(SELECTION_FILE);
        metadataPath = root.resolve(ROM_METADATA_FILE);
        leaderboardPath = root.resolve(LEADERBOARD_FILE);
        try {
            Files.createDirectories(root);
            loadProperties(selectionPath, selections);
            loadProperties(metadataPath, metadata);
            loadProperties(leaderboardPath, leaderboards);
        } catch (IOException error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 读取服务器 ROM 游戏库失败", error);
        }
        refreshCatalogAsync();
    }

    List<RomCatalogEntry> catalog() {
        return catalog(false);
    }

    /** Home cartridges have a two-port default without changing legacy arcade catalogs. */
    List<RomCatalogEntry> homeCatalog() {
        return catalog(true);
    }

    private List<RomCatalogEntry> catalog(boolean home) {
        refreshCatalogAsync();
        List<RomDescriptor> descriptors;
        synchronized (this) {
            descriptors = new ArrayList<>(cache.values());
        }
        return descriptors.stream()
                    .sorted(Comparator.comparing(RomDescriptor::fileName, String.CASE_INSENSITIVE_ORDER))
                    .map(descriptor -> RomCatalogEntry.from(
                            descriptor,
                            displayName(descriptor.sha256()),
                            home ? homeMaxPlayers(descriptor.sha256()) : maxPlayers(descriptor.sha256()),
                            saveMode(descriptor.sha256())))
                    .toList();
    }

    /** Packet lookups never enumerate or read files, including negative lookups. */
    synchronized RomDescriptor find(String sha256) {
        return cache.get(sha256);
    }

    synchronized CompletableFuture<Void> refreshCatalogAsync() {
        if (indexing != null && (!indexing.isDone() || System.nanoTime() < nextRefreshNanos)) {
            return indexing;
        }
        Map<String, RomDescriptor> changes = new LinkedHashMap<>();
        indexChanges = changes;
        nextRefreshNanos = System.nanoTime() + 2_000_000_000L;
        indexing = CompletableFuture.runAsync(() -> {
            Map<String, RomDescriptor> indexed = new LinkedHashMap<>();
            long bytes = 0;
            boolean capacityLimited = false;
            try (var paths = Files.list(repository.root())) {
                List<Path> candidates = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nes"))
                        .limit(4097).sorted().toList();
                if (candidates.size() > 4096) capacityLimited = true;
                var iterator = candidates.subList(0, Math.min(candidates.size(), 4096)).iterator();
                while (iterator.hasNext() && indexed.size() < RomTransferLimits.MAX_CATALOG_ENTRIES) {
                    Path path = iterator.next();
                    try {
                        long size = Files.size(path);
                        if (size <= 0 || size > RomRepository.MAX_ROM_BYTES) continue;
                        if (size > MAX_INDEXED_BYTES - bytes) {
                            capacityLimited = true;
                            continue;
                        }
                        bytes += size; // Bound all reads, including duplicates and malformed headers.
                        RomDescriptor descriptor = repository.load(path.getFileName().toString());
                        // Keep valid-but-unsupported boards visible; selection/store still validate admission.
                        indexed.putIfAbsent(descriptor.sha256(), descriptor);
                    } catch (IOException | IllegalArgumentException ignored) {
                        // Invalid local files do not hide the remainder of the catalog.
                    }
                }
                if (iterator.hasNext()) capacityLimited = true;
                beforeIndexPublish.run();
                synchronized (this) {
                    // Preserve successful concurrent uploads first, then untouched disk entries.
                    // A deletion tombstone always wins, even if the scan read that file earlier.
                    Map<String, RomDescriptor> merged = new LinkedHashMap<>();
                    long mergedBytes = 0;
                    for (Map.Entry<String, RomDescriptor> change : changes.entrySet()) {
                        if (change.getValue() == null) continue;
                        merged.put(change.getKey(), change.getValue());
                        mergedBytes += change.getValue().size();
                    }
                    for (Map.Entry<String, RomDescriptor> entry : indexed.entrySet()) {
                        if (changes.containsKey(entry.getKey())) continue;
                        if (merged.size() >= RomTransferLimits.MAX_CATALOG_ENTRIES
                                || entry.getValue().size() > MAX_INDEXED_BYTES - mergedBytes) {
                            capacityLimited = true;
                            continue;
                        }
                        merged.put(entry.getKey(), entry.getValue());
                        mergedBytes += entry.getValue().size();
                    }
                    cache.clear();
                    cache.putAll(merged);
                    indexChanges = null;
                    if (capacityLimited) warnCapacityLimit();
                }
            } catch (IOException error) {
                FcArcadeMod.LOGGER.error("[PIQ FC] 后台更新 ROM 索引失败，保留上次索引", error);
            } finally {
                synchronized (this) {
                    if (indexChanges == changes) indexChanges = null;
                }
            }
        }, indexExecutor);
        return indexing;
    }

    private void warnCapacityLimit() {
        if (capacityWarningReported) return;
        capacityWarningReported = true;
        indexWarning.accept("[PIQ FC] ROM 索引达到容量限制：最多 256 个游戏 / 128 MiB，"
                + "每次最多检查 4096 个文件；部分磁盘 ROM 暂不可用。文件未删除，请清理游戏库后刷新。");
    }

    synchronized RomDescriptor store(String fileName, String sha256, byte[] bytes) {
        try {
            RomDescriptor existing = cache.get(sha256);
            if (existing != null) {
                NesCompatibility.requireSupported(existing.header());
                return existing;
            }
            long retainedBytes = cache.values().stream().mapToLong(RomDescriptor::size).sum();
            if (cache.size() >= RomTransferLimits.MAX_CATALOG_ENTRIES
                    || bytes.length > MAX_INDEXED_BYTES - retainedBytes) {
                warnCapacityLimit();
                throw new IllegalStateException("ROM 索引容量已满，请先清理不再使用的游戏（上限 256 个 / 128 MiB）");
            }
            RomDescriptor descriptor = repository.storeVerified(fileName, sha256, bytes);
            cache.put(descriptor.sha256(), descriptor);
            if (indexChanges != null) indexChanges.put(descriptor.sha256(), descriptor);
            return descriptor;
        } catch (IOException error) {
            throw new IllegalStateException("保存服务器 ROM 失败", error);
        }
    }

    synchronized boolean delete(String sha256) {
        try {
            RomDescriptor descriptor = cache.get(sha256);
            if (descriptor == null || !Files.deleteIfExists(descriptor.path())) return false;
            cache.remove(sha256);
            if (indexChanges != null) indexChanges.put(sha256, null);
            metadata.remove(sha256);
            metadata.remove(sha256 + ".players");
            metadata.remove(sha256 + ".save");
            metadata.remove(sha256 + ".name");
            selections.entrySet().removeIf(
                    entry -> sha256.equals(entry.getValue()));
            saveProperties(metadataPath, metadata, "PIQ FC ROM metadata");
            saveSelections();
            return true;
        } catch (IOException error) {
            throw new IllegalStateException("删除服务器 ROM 失败", error);
        }
    }

    int maxPlayers(String sha256) {
        String value = metadata.getProperty(
                sha256 + ".players",
                metadata.getProperty(sha256));
        return "2".equals(value) ? 2 : 1;
    }

    /** Missing home metadata defaults to two; explicit/legacy values retain their meaning. */
    int homeMaxPlayers(String sha256) {
        String value = metadata.getProperty(sha256 + ".players", metadata.getProperty(sha256));
        return value == null ? 2 : "2".equals(value) ? 2 : 1;
    }

    void setMaxPlayers(String sha256, int maxPlayers) {
        if (find(sha256) == null) {
            throw new IllegalArgumentException("服务器没有该 ROM");
        }
        if (maxPlayers < 1 || maxPlayers > 2) {
            throw new IllegalArgumentException("ROM 玩家数量无效");
        }
        String key = sha256 + ".players";
        String previous = metadata.getProperty(key);
        metadata.setProperty(key, Integer.toString(maxPlayers));
        try {
            saveProperties(metadataPath, metadata, "PIQ FC ROM player metadata");
        } catch (RuntimeException failure) {
            if (previous == null) metadata.remove(key);
            else metadata.setProperty(key, previous);
            throw failure;
        }
    }

    RomSaveMode saveMode(String sha256) {
        String value = metadata.getProperty(sha256 + ".save", "0");
        try {
            return RomSaveMode.fromId(Integer.parseInt(value));
        } catch (IllegalArgumentException ignored) {
            return RomSaveMode.NONE;
        }
    }

    void setSaveMode(String sha256, RomSaveMode saveMode) {
        if (find(sha256) == null) {
            throw new IllegalArgumentException("服务器没有该 ROM");
        }
        if (saveMode == null) {
            throw new IllegalArgumentException("ROM 存档模式无效");
        }
        String key=sha256+".save",previous=metadata.getProperty(key);
        metadata.setProperty(key,Integer.toString(saveMode.id()));
        try{saveProperties(metadataPath,metadata,"PIQ FC ROM save metadata");}
        catch(RuntimeException failure){if(previous==null)metadata.remove(key);else metadata.setProperty(key,previous);throw failure;}
    }

    String displayName(String sha256) {
        RomDescriptor descriptor = find(sha256);
        if (descriptor == null) return sha256.substring(0, 12);
        String configured = metadata.getProperty(sha256 + ".name", "").strip();
        return configured.isBlank() ? descriptor.fileName() : configured;
    }

    void setDisplayName(String sha256, String displayName) {
        if (find(sha256) == null) {
            throw new IllegalArgumentException("服务器没有该 ROM");
        }
        String normalized = displayName == null ? "" : displayName.strip();
        if (normalized.isBlank() || normalized.length() > 80
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ROM 显示名称无效");
        }
        metadata.setProperty(sha256 + ".name", normalized);
        saveProperties(metadataPath, metadata, "PIQ FC ROM display names");
    }

    String selected(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            ArcadeMode mode
    ) {
        String value = selections.getProperty(key(dimension, anchor, mode), "");
        if (!value.matches(RomRepository.SHA256_PATTERN) || find(value) == null) {
            return "";
        }
        return value;
    }

    void select(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            ArcadeMode mode,
            String sha256
    ) {
        RomDescriptor descriptor = find(sha256);
        if (descriptor == null) {
            throw new IllegalArgumentException("服务器没有该 ROM");
        }
        NesCompatibility.requireSupported(descriptor.header());
        selections.setProperty(key(dimension, anchor, mode), sha256);
        saveSelections();
    }

    boolean leaderboardEnabled(
            ResourceKey<Level> dimension,
            BlockPos anchor
    ) {
        return leaderboardEnabledFromSetting(leaderboards.getProperty(
                leaderboardKey(dimension, anchor)));
    }

    static boolean leaderboardEnabledFromSetting(String stored) {
        // New/unconfigured cabinets stay black while idle. Explicitly saved
        // true/false choices are preserved; do not rewrite players' settings.
        return Boolean.parseBoolean(stored);
    }

    void setLeaderboardEnabled(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            boolean enabled
    ) {
        leaderboards.setProperty(
                leaderboardKey(dimension, anchor),
                Boolean.toString(enabled));
        saveProperties(
                leaderboardPath,
                leaderboards,
                "PIQ FC arcade leaderboard visibility");
    }

    List<ArcadeSelection> configuredSelections() {
        return selections.stringPropertyNames().stream()
                .map(this::parseSelection)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ArcadeSelection parseSelection(String key) {
        String[] parts = key.split("\\|", 3);
        if (parts.length != 3) return null;
        ResourceLocation location = ResourceLocation.tryParse(parts[0]);
        String[] coordinates = parts[1].split(",", 3);
        String sha256 = selections.getProperty(key, "");
        if (location == null || coordinates.length != 3
                || !sha256.matches(RomRepository.SHA256_PATTERN)
                || find(sha256) == null) {
            return null;
        }
        try {
            return new ArcadeSelection(
                    ResourceKey.create(Registries.DIMENSION, location),
                    new BlockPos(
                            Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]),
                            Integer.parseInt(coordinates[2])),
                    ArcadeMode.valueOf(parts[2]),
                    sha256);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void saveSelections() {
        saveProperties(
                selectionPath,
                selections,
                "PIQ FC arcade ROM selections");
    }

    private static void loadProperties(Path path, Properties target) throws IOException {
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            target.load(reader);
        }
    }

    private static void saveProperties(
            Path path,
            Properties properties,
            String comment
    ) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(
                    path.getParent(),
                    ".piq-properties-",
                    ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(
                        temporary,
                        StandardCharsets.UTF_8)) {
                    properties.store(writer, comment);
                }
                try {
                    Files.move(
                            temporary,
                            path,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(
                            temporary,
                            path,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new IllegalStateException("保存街机 ROM 选择失败", error);
        }
    }

    private static String key(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            ArcadeMode mode
    ) {
        return dimension.location() + "|"
                + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + "|" + mode.name();
    }

    private static String leaderboardKey(
            ResourceKey<Level> dimension,
            BlockPos anchor
    ) {
        return dimension.location() + "|"
                + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
    }

    record ArcadeSelection(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            ArcadeMode mode,
            String sha256
    ) {
    }
}
