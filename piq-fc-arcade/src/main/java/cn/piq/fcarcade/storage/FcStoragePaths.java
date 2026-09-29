package cn.piq.fcarcade.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** FC areas beneath the shared data root. Legacy FC area imports remain copy-only. */
public final class FcStoragePaths {
    public enum Area {
        ROMS("roms", "fc-roms"),
        COVERS("covers", "fc-covers"),
        SKINS("skins", "piq_fc_arcade/skins"),
        COVER_CACHE("cache/covers", "fc-cartridge-cover-cache"),
        SKIN_CACHE("cache/skins", "piq_fc_arcade/skin-cache"),
        SHARED_COVERS("shared/covers", "fc-cartridge-covers"),
        SHARED_SKINS("shared/skins", "fc-skins"),
        SAVES("saves", "fc-saves"),
        SCORES("scores", "fc-scores"),
        CLIENT_CONFIG("config/piq-fc-arcade-client.properties", "config/piq-fc-arcade-client.properties", true),
        SERVER_CONFIG("config/piq_fc_arcade-server.properties", "config/piq_fc_arcade-server.properties", true),
        CALIBRATION("reports/calibration", "logs");

        private final String destination;
        private final String legacy;
        private final boolean file;
        Area(String destination, String legacy) { this(destination, legacy, false); }
        Area(String destination, String legacy, boolean file) {
            this.destination = destination; this.legacy = legacy; this.file = file;
        }
    }

    private static final Set<Path> PREPARED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final String MARKER = "PIQ-FC copy migration v1\n";
    private static final int MAX_MIGRATION_FILES = 100_000;
    private FcStoragePaths() {}

    public static Path root(Path base) { return cn.piq.retro.storage.ConsoleStorage.location(base).resolve("piq-fc"); }
    public static Path path(Path base, Area area) { return root(base).resolve(area.destination); }

    /** Call before constructing an authoritative store. Failure must not fall back to an empty store. */
    public static Path prepareUnchecked(Path base, Area area) {
        try { return prepare(base, area); }
        catch (IOException error) { throw new UncheckedIOException("FC storage initialization failed: " + path(base, area), error); }
    }

    /** Only this explicit entry point copies files; old files are never moved, overwritten or deleted. */
    public static Path prepare(Path base, Area area) throws IOException {
        try { cn.piq.retro.storage.ConsoleStorage.root(base); }
        catch (UncheckedIOException error) { throw error.getCause(); }
        Path absoluteBase = base.toAbsolutePath().normalize();
        Path destination = path(absoluteBase, area);
        if (PREPARED.contains(destination)) return destination;
        synchronized (LOCKS.computeIfAbsent(destination, ignored -> new Object())) {
            if (PREPARED.contains(destination)) return destination;
            return prepareFirst(absoluteBase, destination, area);
        }
    }

    private static Path prepareFirst(Path absoluteBase, Path destination, Area area) throws IOException {
        if (!Files.isDirectory(absoluteBase)) throw new IOException("FC base directory is unavailable: " + absoluteBase);
        Path realBase = absoluteBase.toRealPath();
        Path target = root(realBase).resolve(area.destination);
        Path metadata = root(realBase).resolve(".migration");
        ensureDirectory(realBase, metadata);
        ensureDirectory(realBase, area.file ? target.getParent() : target);
        validateExisting(realBase, target);
        Path marker = metadata.resolve(area.name().toLowerCase(java.util.Locale.ROOT) + ".done");
        validateExisting(realBase, marker);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 128
                    || !Files.readString(marker, StandardCharsets.UTF_8).equals(MARKER)) {
                throw new IOException("Invalid FC migration marker; refusing legacy fallback: " + marker);
            }
            PREPARED.add(destination);
            return destination;
        }
        List<String> conflicts = new ArrayList<>();
        int[] count = {0};
        Path source = realBase.resolve(area.legacy);
        validateExisting(realBase, source);
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            if (area.file) {
                copyFile(realBase, source, target, conflicts, count);
            } else if (area == Area.CALIBRATION) {
                // Do not enumerate or copy other mods' logs.
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected FC log directory: " + source);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(source, "piq-fc-scorecal-*.txt")) {
                    for (Path file : entries) copyFile(realBase, file, target.resolve(file.getFileName()), conflicts, count);
                }
            } else {
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected FC legacy directory: " + source);
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        validateExisting(realBase, dir);
                        ensureDirectory(realBase, target.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        copyFile(realBase, file, target.resolve(source.relativize(file)), conflicts, count);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        StringBuilder report = new StringBuilder("FC copy migration v1\narea=").append(area.name())
                .append("\nnew-location-is-authoritative=true\nlegacy-files-preserved=true\nfiles-checked=")
                .append(count[0]).append("\nconflicts=").append(conflicts.size()).append('\n');
        for (String conflict : conflicts) report.append("kept-new-and-preserved-old=").append(conflict).append('\n');
        Path reportPath = metadata.resolve(area.name().toLowerCase(java.util.Locale.ROOT) + ".report.txt");
        reportPath = writeReport(realBase, reportPath, report.toString());
        // Commit last: a failed copy never exposes an empty authoritative save store.
        writeNew(marker, MARKER);
        PREPARED.add(destination);
        if (!conflicts.isEmpty()) System.getLogger(FcStoragePaths.class.getName()).log(System.Logger.Level.WARNING,
                "FC migration kept {0} conflicting legacy files; new files are authoritative. Report: {1}", conflicts.size(), reportPath);
        return destination;
    }

    private static void copyFile(Path base, Path source, Path target, List<String> conflicts, int[] count) throws IOException {
        if (++count[0] > MAX_MIGRATION_FILES) throw new IOException("FC migration file limit exceeded; legacy files were preserved");
        validateExisting(base, source);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Refusing non-regular FC migration source: " + source);
        ensureDirectory(base, target.getParent());
        validateExisting(base, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("FC migration target is not a file: " + target);
            if (Files.mismatch(source, target) != -1) conflicts.add(base.relativize(source).toString().replace('\n', '_').replace('\r', '_'));
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".fc-migrate-", ".tmp");
        try {
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) { input.transferTo(output); }
            validateExisting(base, source);
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()) || Files.mismatch(source, temporary) != -1) {
                throw new IOException("FC legacy file changed during copy; original preserved: " + source);
            }
            validateExisting(base, target);
            // Deliberately no REPLACE_EXISTING or ATOMIC_MOVE (some providers replace on atomic move).
            Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void ensureDirectory(Path base, Path directory) throws IOException {
        if (!directory.normalize().startsWith(base)) throw new IOException("FC path escapes base: " + directory);
        Path current = base;
        for (Path segment : base.relativize(directory)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current); }
                catch (java.nio.file.FileAlreadyExistsException concurrentCreator) {
                    // Different areas can prepare concurrently and share parent directories.
                }
            }
            validateExisting(base, current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("FC path is not a directory: " + current);
        }
    }

    private static void validateExisting(Path base, Path path) throws IOException {
        if (!path.normalize().startsWith(base)) throw new IOException("FC path escapes base: " + path);
        Path current = base;
        for (Path segment : base.relativize(path)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !current.toRealPath().equals(current)) {
                throw new IOException("Refusing linked/redirected FC path: " + current);
            }
        }
    }

    private static Path writeReport(Path base, Path report, String text) throws IOException {
        validateExisting(base, report);
        // A previous interrupted attempt's report is retained; retries get their own report.
        if (Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
            report = Files.createTempFile(report.getParent(), report.getFileName() + ".retry-", ".txt");
            Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } else writeNew(report, text);
        return report;
    }

    private static void writeNew(Path target, String text) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".fc-migrate-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
