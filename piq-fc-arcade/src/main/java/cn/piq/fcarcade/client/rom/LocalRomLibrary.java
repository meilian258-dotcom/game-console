package cn.piq.fcarcade.client.rom;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded metadata-only discovery for explicit, local ROM folders; no emulator or Minecraft APIs. */
public final class LocalRomLibrary {
    public static final int MAX_ENTRIES = 512;
    public static final int MAX_INSPECTED = 2048;
    private static final long SCAN_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "piq-local-rom-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { IO.allowCoreThreadTimeOut(true); }

    private LocalRomLibrary() {}

    public record Entry(Path path, String fileName, long bytes) {}
    public record Scan(List<Entry> entries, int skipped, boolean limited) {
        public Scan { entries = List.copyOf(entries); }
    }

    /** Pure paths. The main mod initializes the shared root before registering clients. */
    public static Path sfcDirectory(Path gameDir) {
        return cn.piq.retro.storage.ConsoleStorage.location(gameDir).resolve("piq-sfc-home/roms");
    }
    public static Path arcadeDirectory(Path gameDir) {
        return cn.piq.retro.storage.ConsoleStorage.location(gameDir).resolve("piq-native-arcade/roms");
    }

    /** Explicit creation only. Never follow an existing link or replace a file. */
    public static Path prepare(Path directory) throws IOException {
        Path path = absolute(directory);
        Path current = path.getRoot();
        realDirectory(current);
        for (Path part : path) {
            interrupted();
            current = current.resolve(part);
            try { realDirectory(current); }
            catch (NoSuchFileException missing) {
                // Recheck all existing parents before creation; createDirectory never replaces.
                validateDirectory(current.getParent());
                try { Files.createDirectory(current); }
                catch (FileAlreadyExistsException concurrentCreator) { /* Validate below. */ }
                realDirectory(current);
            }
        }
        validateDirectory(path);
        return path;
    }

    /**
     * Read direct-child metadata only. The budget is cooperative between filesystem operations;
     * an individual OS filesystem call cannot be given a hard deadline by this Java API.
     * A listed file is not trusted ROM content: the consumer must revalidate and use its loader.
     */
    public static Scan scan(Path directory, Set<String> extensions, Set<String> excludedNames) throws IOException {
        Path path = absolute(directory);
        Set<String> suffixes = extensions(extensions);
        Set<String> excluded = new HashSet<>();
        if (excludedNames != null) for (String name : excludedNames) {
            if (!safeName(name)) throw new IOException("Invalid excluded ROM filename");
            excluded.add(name.toLowerCase(Locale.ROOT));
        }
        validateDirectory(path);
        BasicFileAttributes before = realDirectory(path);
        List<Entry> entries = new ArrayList<>();
        int inspected = 0, skipped = 0;
        boolean limited = false;
        long started = System.nanoTime();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path file : stream) {
                interrupted();
                if (entries.size() >= MAX_ENTRIES || inspected >= MAX_INSPECTED
                        || System.nanoTime() - started >= SCAN_NANOS) { limited = true; break; }
                inspected++;
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!safeName(name) || excluded.contains(lower) || suffixes.stream().noneMatch(lower::endsWith)) {
                    skipped++; continue;
                }
                try {
                    BasicFileAttributes attributes = realFile(file);
                    entries.add(new Entry(file.toAbsolutePath().normalize(), name, attributes.size()));
                } catch (IOException rejectedOrChanged) { skipped++; }
            }
        }
        interrupted();
        validateDirectory(path);
        if (!Objects.equals(before.fileKey(), realDirectory(path).fileKey()))
            throw new IOException("ROM directory changed while listing; refresh and retry");
        entries.sort(Comparator.comparing(Entry::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::fileName));
        return new Scan(entries, skipped, limited);
    }

    /** Recheck a selected path immediately before the caller reads it; opens no content. */
    public static void validateFile(Path supplied) throws IOException {
        Path path = absolute(supplied);
        if (path.getFileName() == null || !safeName(path.getFileName().toString()))
            throw new IOException("Invalid ROM filename");
        validateDirectory(path.getParent());
        realFile(path);
    }

    /** No CallerRunsPolicy: a full queue must never move file I/O onto the render thread. */
    public static boolean submit(Runnable task) {
        if (task == null) return false;
        try { IO.execute(task); return true; }
        catch (RejectedExecutionException busy) { return false; }
    }

    private static Path absolute(Path path) throws IOException {
        if (path == null) throw new IOException("ROM directory/path is missing");
        Path result = path.toAbsolutePath().normalize();
        if (result.getRoot() == null) throw new IOException("ROM path has no filesystem root");
        return result;
    }

    private static void validateDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("ROM directory is missing");
        Path current = directory.getRoot();
        realDirectory(current);
        for (Path part : directory) {
            interrupted();
            current = current.resolve(part);
            realDirectory(current);
        }
    }

    private static BasicFileAttributes realDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = realAttributes(path);
        if (!attributes.isDirectory()) throw new IOException("ROM path component is not a directory: " + path);
        return attributes;
    }

    private static BasicFileAttributes realFile(Path path) throws IOException {
        BasicFileAttributes attributes = realAttributes(path);
        if (!attributes.isRegularFile() || attributes.size() < 0)
            throw new IOException("ROM entry is not a regular file: " + path);
        return attributes;
    }

    private static BasicFileAttributes realAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("Linked or redirected ROM paths are not allowed: " + path);
        return attributes;
    }

    private static Set<String> extensions(Set<String> values) throws IOException {
        if (values == null || values.isEmpty() || values.size() > 16) throw new IOException("ROM extensions are missing or excessive");
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value == null || !value.matches("\\.?[A-Za-z0-9]{1,12}")) throw new IOException("Invalid ROM extension");
            result.add((value.startsWith(".") ? value : "." + value).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isBlank() && value.length() <= 255 && !value.equals(".") && !value.equals("..")
                && value.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\' || c == ':');
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ROM directory operation cancelled");
    }
}
