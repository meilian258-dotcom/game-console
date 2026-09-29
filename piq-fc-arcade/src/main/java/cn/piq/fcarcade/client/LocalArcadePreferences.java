package cn.piq.fcarcade.client;

import cn.piq.fcarcade.storage.FcStoragePaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Read once per client process; only an explicit command writes the file. */
final class LocalArcadePreferences {
    static final int DEFAULT_MAX_SPECTATORS = 2;
    static final String FILE_NAME = "piq-fc-arcade-client.properties";
    private static final String LIMIT_KEY = "maxSimulatedSpectators";
    private static final String ASPECT_KEY = "dualScreenAspect";
    private static final String HAND_SIZE_KEY = "controllerHandSize";
    private double controllerHandSize=ControllerPoseLayout.DEFAULT_HAND_SIZE;
    private cn.piq.fcarcade.layout.ScreenAspectFit.Aspect dualScreenAspect = cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE;

    private final Path path;
    private final Path migrationBase;
    private boolean migrationFailed;
    private final Properties properties = new Properties();
    private boolean loaded;
    private int maximumSpectators = DEFAULT_MAX_SPECTATORS;

    LocalArcadePreferences(Path path) {
        this(path, null);
    }

    private LocalArcadePreferences(Path path, Path migrationBase) {
        this.path = path;
        this.migrationBase = migrationBase;
    }

    static LocalArcadePreferences forGameDirectory(Path base) {
        return new LocalArcadePreferences(FcStoragePaths.path(base, FcStoragePaths.Area.CLIENT_CONFIG), base);
    }

    void loadOnce() throws IOException {
        if (loaded) return;
        loaded = true;
        if (migrationBase != null) {
            try { FcStoragePaths.prepare(migrationBase, FcStoragePaths.Area.CLIENT_CONFIG); }
            catch (IOException error) { migrationFailed = true; throw error; }
        }
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid local FC preferences", error);
        }
        try {
            int value = Integer.parseInt(properties.getProperty(LIMIT_KEY, "2").trim());
            if (value >= 0 && value <= 8) maximumSpectators = value;
        } catch (NumberFormatException ignored) {
            // Invalid hand-edited values use the documented default without
            // overwriting the user's file during startup or a render callback.
        }
        try { dualScreenAspect = cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.parse(properties.getProperty(ASPECT_KEY,"4:3").trim()); }
        catch (IllegalArgumentException ignored) { /* Keep 4:3 without rewriting an invalid user value. */ }
        try { setControllerHandSize(Double.parseDouble(properties.getProperty(HAND_SIZE_KEY,"1.18"))); }
        catch(IllegalArgumentException ignored) { /* Preserve invalid user text until explicit save. */ }
    }

    cn.piq.fcarcade.layout.ScreenAspectFit.Aspect dualScreenAspect() { return dualScreenAspect; }
    double controllerHandSize() { return controllerHandSize; }
    void setControllerHandSize(double value) {
        if(!Double.isFinite(value)||value<1||value>1.30)throw new IllegalArgumentException("Hand size must be 1.0..1.3");
        controllerHandSize=value;
    }
    void setDualScreenAspect(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect aspect) {
        dualScreenAspect = java.util.Objects.requireNonNull(aspect);
    }

    int maximumSpectators() {
        return maximumSpectators;
    }

    void setMaximumSpectators(int maximum) {
        if (maximum < 0 || maximum > 8) {
            throw new IllegalArgumentException("Spectator limit must be 0..8");
        }
        maximumSpectators = maximum;
    }

    void save() throws IOException {
        if (migrationFailed) throw new IOException("FC 本地设置迁移失败；不会用默认设置覆盖旧数据，请查看日志并重启后重试");
        if (migrationBase != null) FcStoragePaths.prepare(migrationBase, FcStoragePaths.Area.CLIENT_CONFIG);
        properties.setProperty(LIMIT_KEY, Integer.toString(maximumSpectators));
        properties.setProperty(ASPECT_KEY, dualScreenAspect.label());
        properties.setProperty(HAND_SIZE_KEY, Double.toString(controllerHandSize));
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "piq-fc-client-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "PIQ FC local client preferences; spectators 0..8");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
