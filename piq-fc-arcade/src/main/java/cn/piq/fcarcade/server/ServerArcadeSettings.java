package cn.piq.fcarcade.server;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.config.ArcadeGlobalSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class ServerArcadeSettings {
    private static final String VIEW_DISTANCE = "viewDistance";
    private static final String AUDIO_DISTANCE = "audioDistance";
    private static final String AUDIO_VOLUME_PERCENT = "audioVolumePercent";
    private static final String SAVE_RETENTION_DAYS = "saveRetentionDays";
    private static final String LEADERBOARD_PAGE_SECONDS =
            "leaderboardPageSeconds";
    static final int DEFAULT_LEADERBOARD_PAGE_SECONDS = 3;

    private final Path path;
    private ArcadeGlobalSettings settings;
    private int leaderboardPageSeconds = DEFAULT_LEADERBOARD_PAGE_SECONDS;

    ServerArcadeSettings(Path path) {
        this.path = path.toAbsolutePath().normalize();
        settings = load();
    }

    ArcadeGlobalSettings get() {
        return settings;
    }

    void set(ArcadeGlobalSettings next) {
        if (next == null) throw new IllegalArgumentException("街机全局设置不能为空");
        settings = next;
        save();
    }

    int leaderboardPageSeconds() {
        return leaderboardPageSeconds;
    }

    void setLeaderboardPageSeconds(int seconds) {
        if (seconds < 1 || seconds > 60) {
            throw new IllegalArgumentException(
                    "Leaderboard page seconds must be between 1 and 60");
        }
        leaderboardPageSeconds = seconds;
        save();
    }

    private ArcadeGlobalSettings load() {
        if (!Files.isRegularFile(path)) return ArcadeGlobalSettings.DEFAULT;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            leaderboardPageSeconds = clamp(
                    integer(properties, LEADERBOARD_PAGE_SECONDS,
                            DEFAULT_LEADERBOARD_PAGE_SECONDS),
                    1,
                    60);
            return new ArcadeGlobalSettings(
                    integer(properties, VIEW_DISTANCE,
                            ArcadeGlobalSettings.DEFAULT_VIEW_DISTANCE),
                    integer(properties, AUDIO_DISTANCE,
                            ArcadeGlobalSettings.DEFAULT_AUDIO_DISTANCE),
                    integer(properties, AUDIO_VOLUME_PERCENT,
                            ArcadeGlobalSettings.DEFAULT_AUDIO_VOLUME_PERCENT),
                    integer(properties, SAVE_RETENTION_DAYS,
                            ArcadeGlobalSettings.DEFAULT_SAVE_RETENTION_DAYS));
        } catch (IOException | IllegalArgumentException error) {
            FcArcadeMod.LOGGER.error(
                    "[PIQ FC] 读取全局设置失败，使用默认值：{}",
                    path,
                    error);
            return ArcadeGlobalSettings.DEFAULT;
        }
    }

    private void save() {
        Properties properties = new Properties();
        properties.setProperty(VIEW_DISTANCE,
                Integer.toString(settings.viewDistance()));
        properties.setProperty(AUDIO_DISTANCE,
                Integer.toString(settings.audioDistance()));
        properties.setProperty(AUDIO_VOLUME_PERCENT,
                Integer.toString(settings.audioVolumePercent()));
        properties.setProperty(SAVE_RETENTION_DAYS,
                Integer.toString(settings.saveRetentionDays()));
        properties.setProperty(LEADERBOARD_PAGE_SECONDS,
                Integer.toString(leaderboardPageSeconds));
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(
                    path.getParent(),
                    ".piq-fc-settings-",
                    ".tmp");
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8)) {
                properties.store(writer, "PIQ FC Arcade server settings");
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
            temporary = null;
        } catch (IOException error) {
            throw new IllegalStateException("保存街机全局设置失败", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int integer(
            Properties properties,
            String key,
            int defaultValue
    ) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
