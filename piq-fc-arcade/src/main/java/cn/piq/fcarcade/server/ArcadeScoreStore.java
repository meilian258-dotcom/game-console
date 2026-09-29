package cn.piq.fcarcade.server;

import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.score.RoadRaceScoreRule;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class ArcadeScoreStore {
    private final Path file;
    private final Map<String, Map<UUID, ScoreEntry>> entries = new HashMap<>();
    private boolean dirty;

    ArcadeScoreStore(Path file) {
        this.file = file;
        load();
    }

    synchronized boolean submit(
            String romSha256,
            UUID playerId,
            String playerName,
            int score
    ) {
        String sha = normalizeSha(romSha256);
        if (!sha.matches(RomRepository.SHA256_PATTERN)
                || playerId == null || score <= 0
                || score > RoadRaceScoreRule.MAX_SCORE) {
            return false;
        }
        Map<UUID, ScoreEntry> scores = entries.computeIfAbsent(
                sha,
                ignored -> new HashMap<>());
        ScoreEntry previous = scores.get(playerId);
        if (previous != null && previous.score() >= score) return false;
        String safeName = playerName == null || playerName.isBlank()
                ? playerId.toString()
                : playerName;
        scores.put(playerId, new ScoreEntry(
                playerId,
                safeName,
                score,
                System.currentTimeMillis()));
        dirty = true;
        return true;
    }

    synchronized int personalBest(String romSha256, UUID playerId) {
        ScoreEntry entry = entries
                .getOrDefault(normalizeSha(romSha256), Map.of())
                .get(playerId);
        return entry == null ? 0 : entry.score();
    }

    public synchronized List<ScoreEntry> top(String romSha256, int limit) {
        if (limit <= 0) return List.of();
        return entries.getOrDefault(normalizeSha(romSha256), Map.of())
                .values()
                .stream()
                .sorted(Comparator.comparingInt(ScoreEntry::score)
                        .reversed()
                        .thenComparingLong(ScoreEntry::updatedAt)
                        .thenComparing(ScoreEntry::playerName))
                .limit(limit)
                .toList();
    }

    synchronized void flush() {
        if (!dirty) return;
        Properties properties = new Properties();
        entries.forEach((sha, scores) -> scores.forEach((playerId, entry) -> {
            String name = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.playerName().getBytes(StandardCharsets.UTF_8));
            properties.setProperty(
                    sha + "." + playerId,
                    entry.score() + "|" + entry.updatedAt() + "|" + name);
        }));
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "PIQ FC Arcade leaderboard");
            }
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to save FC leaderboard", error);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read FC leaderboard", error);
        }
        for (String key : properties.stringPropertyNames()) {
            int separator = key.indexOf('.');
            if (separator <= 0 || separator >= key.length() - 1) continue;
            String sha = normalizeSha(key.substring(0, separator));
            if (!sha.matches(RomRepository.SHA256_PATTERN)) continue;
            try {
                UUID playerId = UUID.fromString(key.substring(separator + 1));
                String[] fields = properties.getProperty(key, "").split("\\|", 3);
                if (fields.length != 3) continue;
                int score = Integer.parseInt(fields[0]);
                long updatedAt = Long.parseLong(fields[1]);
                String playerName = new String(
                        Base64.getUrlDecoder().decode(fields[2]),
                        StandardCharsets.UTF_8);
                if (score <= 0 || score > RoadRaceScoreRule.MAX_SCORE) continue;
                entries.computeIfAbsent(sha, ignored -> new HashMap<>())
                        .put(playerId, new ScoreEntry(
                                playerId,
                                playerName,
                                score,
                                updatedAt));
            } catch (IllegalArgumentException ignored) {
                // Ignore one malformed entry without discarding valid scores.
            }
        }
    }

    private static String normalizeSha(String sha) {
        return sha == null ? "" : sha.toLowerCase(java.util.Locale.ROOT);
    }

    public record ScoreEntry(
            UUID playerId,
            String playerName,
            int score,
            long updatedAt
    ) {
    }
}
