// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.storage;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Disposable core working copies only. Never scans OS temp, ROM libraries or save stores.
 * Call configure with the ORIGINAL instance root, once before starting cores. No Minecraft dependency.
 * Cross-JVM locks and process start times protect simultaneous clients and orphaned native workers.
 */
public final class RuntimeWorkspace implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("PIQ-RuntimeWorkspace");
    private static final String MARK = "PIQ-RUNTIME-WORKSPACE-1";
    private static final long MIB = 1024L * 1024;
    private static final Set<RuntimeWorkspace> LIVE = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "PIQ-runtime-cleanup"); thread.setDaemon(true); return thread;
    });
    private static volatile Path instance = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private final Path root, directory;
    private final FileChannel channel;
    private final FileLock lease;
    private volatile Process child;
    // In-process native calls cannot be killed safely, including during JVM shutdown hooks.
    // Keep both the lease and directory until their owning thread confirms native teardown.
    private int nativePins;
    private boolean finished, exitQueued;
    private int attempts;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<RuntimeWorkspace> live = List.copyOf(LIVE);
            for (var workspace : live) workspace.stopChild();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            for (var workspace : live) {
                Process process = workspace.child;
                if (process != null) try {
                    process.waitFor(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                workspace.close();
            }
        }, "PIQ-runtime-shutdown"));
    }
    private RuntimeWorkspace(Path root, Path directory, FileChannel channel, FileLock lease) {
        this.root = root; this.directory = directory; this.channel = channel; this.lease = lease;
    }
    /** Configuring paths does not perform IO on the game thread. Startup recovery runs in the background. */
    public static void configure(Path originalInstance) {
        instance = Objects.requireNonNull(originalInstance).toAbsolutePath().normalize();
        Path selected = instance;
        CLEANER.execute(() -> { try { reap(selected); } catch (IOException e) { warn("启动回收暂未完成", e); } });
    }
    public static RuntimeWorkspace create(String kind, long requiredBytes) throws IOException {
        return create(instance, kind, requiredBytes);
    }
    static synchronized RuntimeWorkspace create(Path base, String kind, long requiredBytes) throws IOException {
        if (!Set.of("netplay", "libretro", "fc-legacy").contains(kind)) throw new IllegalArgumentException("Runtime kind");
        if (requiredBytes < 0 || requiredBytes > 1024 * MIB) throw new IllegalArgumentException("Runtime size");
        Path root = root(base);
        try (var guard = guard(root)) {
            reapLocked(root);
            requireSpace(root, requiredBytes, Files.getFileStore(root).getUsableSpace());
            Path dir = Files.createDirectory(root.resolve(kind + "-" + UUID.randomUUID()));
            Files.writeString(dir.resolve("owner"), MARK + "\n" + dir.getFileName() + "\n" + identity(ProcessHandle.current()), StandardOpenOption.CREATE_NEW);
            FileChannel channel = FileChannel.open(dir.resolve("lease"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                RuntimeWorkspace workspace = new RuntimeWorkspace(root, dir, channel, channel.lock());
                LIVE.add(workspace); return workspace;
            } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
        }
    }
    public Path directory() { return directory; }
    public synchronized AutoCloseable pinNative() {
        if (finished) throw new IllegalStateException("Runtime workspace closed");
        nativePins++;
        return new AutoCloseable() {
            private boolean released;
            @Override public void close() {
                synchronized (RuntimeWorkspace.this) {
                    if (!released) { released = true; nativePins--; }
                }
            }
        };
    }
    /** Mark launch BEFORE spawning, so an abrupt parent crash cannot make an untracked child disposable. */
    public synchronized Process start(ProcessBuilder builder) throws IOException {
        if (finished || child != null) throw new IOException("运行会话已关闭或已启动");
        Files.writeString(directory.resolve("launching"), MARK, StandardOpenOption.CREATE_NEW);
        try { child = builder.start(); }
        catch (IOException failure) { Files.deleteIfExists(directory.resolve("launching")); throw failure; }
        try { Files.writeString(directory.resolve("child"), identity(child.toHandle()), StandardOpenOption.CREATE_NEW); }
        catch (IOException failure) { child.destroyForcibly(); throw failure; }
        return child;
    }
    public static boolean diskFull(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("磁盘空间不足") || value.contains("磁碟空間不足") || value.contains("no space left")
                || value.contains("not enough space on the disk") || value.contains("disk full") || value.contains("enospc");
    }
    static void requireSpace(Path root, long required, long available) throws IOException {
        if (available < required + 16 * MIB) throw new IOException("磁盘空间不足：运行目录 " + root
                + "，需预留 " + ((required + 16 * MIB + MIB - 1) / MIB) + " MiB，可用 " + available / MIB + " MiB");
    }
    public String failureMessage(String message) {
        return diskFull(message) ? "磁盘空间不足：运行目录 " + directory + "；请释放该盘空间后重试" : message;
    }
    private synchronized void stopChild() { if (child != null && child.isAlive()) child.destroyForcibly(); }
    /** Only after the caller stops the core. Never deletes while that native worker is still alive. */
    @Override public synchronized void close() {
        if (finished) return;
        if (nativePins != 0) return;
        if (child != null && child.isAlive()) {
            if (!exitQueued) { exitQueued = true; child.onExit().thenRun(() -> CLEANER.execute(this::close)); }
            return;
        }
        try {
            synchronized (RuntimeWorkspace.class) {
                checked(root, true);
                try (var guard = guard(root)) { remove(directory, root, () -> { if (lease.isValid()) lease.release(); channel.close(); }); }
            }
            finished = true; LIVE.remove(this);
        } catch (IOException | RuntimeException failure) {
            if (++attempts < 4) CLEANER.schedule(this::close, attempts * 300L, TimeUnit.MILLISECONDS);
            else {
                try { if (lease.isValid()) lease.release(); channel.close(); } catch (IOException ignored) { }
                finished = true; LIVE.remove(this);
                warn("运行临时目录清理失败，保留待下次启动回收：" + directory, failure);
            }
        }
    }
    static synchronized int reap(Path base) throws IOException {
        Path root = root(base);
        try (var guard = guard(root)) { return reapLocked(root); }
    }
    private static int reapLocked(Path root) throws IOException {
        int removed = 0, seen = 0;
        try (var dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (++seen > 4096) break; // Bound work, no recursive search for candidates.
                if (!dir.getFileName().toString().matches("(netplay|libretro|fc-legacy)-[0-9a-f-]{36}")) continue;
                try {
                    checked(dir, true);
                    List<String> owner = smallFile(dir.resolve("owner"));
                    if (owner.size() != 4 || !MARK.equals(owner.get(0)) || !dir.getFileName().toString().equals(owner.get(1))
                            || alive(owner.subList(2, 4))) continue;
                    if (Files.exists(dir.resolve("launching"), LinkOption.NOFOLLOW_LINKS)) {
                        // Unknown launch state is preserved for manual review, never guessed to be dead.
                        if (!Files.exists(dir.resolve("child"), LinkOption.NOFOLLOW_LINKS) || alive(smallFile(dir.resolve("child")))) continue;
                    }
                    Path lockPath = dir.resolve("lease");
                    if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) checked(lockPath, false);
                    try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        FileLock lock;
                        try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { continue; }
                        if (lock == null) continue;
                        try { remove(dir, root, () -> { lock.release(); channel.close(); }); removed++; }
                        finally { if (lock.isValid()) lock.release(); }
                    }
                } catch (IOException | RuntimeException failure) { warn("保留无法安全回收的运行目录：" + dir, failure); }
            }
        }
        if (removed != 0) LOG.log(System.Logger.Level.INFO, "已回收 " + removed + " 个过期运行目录：" + root);
        return removed;
    }
    private static boolean alive(List<String> identity) throws IOException {
        if (identity.size() != 2) throw new IOException("进程身份记录异常");
        try {
            long pid = Long.parseLong(identity.get(0));
            if (pid <= 0) throw new IllegalArgumentException("pid");
            String time = identity.get(1);
            if (!time.equals("unknown")) Instant.parse(time);
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) return false;
            var started = process.get().info().startInstant();
            // Unknown/access denied is conservatively live. Start time prevents PID reuse confusion.
            return time.equals("unknown") || started.isEmpty() || started.get().toString().equals(time);
        } catch (IllegalArgumentException e) { throw new IOException("进程身份记录异常", e); }
    }
    private static String identity(ProcessHandle process) {
        return process.pid() + "\n" + process.info().startInstant().map(Instant::toString).orElse("unknown") + "\n";
    }
    private static List<String> smallFile(Path path) throws IOException {
        checked(path, false);
        if (Files.size(path) > 1024) throw new IOException("运行目录标记过大");
        return Files.readAllLines(path);
    }
    private static Path root(Path base) throws IOException {
        Path real = base.toAbsolutePath().normalize().toRealPath();
        Path current = real;
        for (String name : List.of("game-console", "runtime-sessions")) {
            current = current.resolve(name);
            try { Files.createDirectory(current); } catch (FileAlreadyExistsException exists) { }
            checked(current, true);
        }
        return current;
    }
    private static Guard guard(Path root) throws IOException {
        Path file = root.resolve("cleanup.lock");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) checked(file, false);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("运行目录正由另一个游戏实例维护，请稍后重试");
            return new Guard(channel, lock);
        } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
    }
    private record Guard(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    @FunctionalInterface private interface Release { void run() throws IOException; }
    private static void remove(Path dir, Path root, Release release) throws IOException {
        if (!dir.getParent().equals(root) || !dir.getFileName().toString().matches("(netplay|libretro|fc-legacy)-[0-9a-f-]{36}")) throw new IOException("拒绝运行目录越界");
        checked(root, true); checked(dir, true);
        // Preflight the ENTIRE tree before touching it: never follow junctions/symlinks or special files.
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            var iter = walk.iterator();
            while (iter.hasNext()) {
                Path path = iter.next();
                if (paths.size() >= 4096 || !path.normalize().startsWith(dir)) throw new IOException("运行目录边界异常");
                checked(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)); paths.add(path);
            }
        }
        paths.sort(Comparator.reverseOrder());
        Set<Path> markers = Set.of(dir.resolve("owner"), dir.resolve("lease"), dir.resolve("launching"), dir.resolve("child"), dir);
        for (Path path : paths) if (!markers.contains(path)) { checked(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)); Files.deleteIfExists(path); }
        release.run(); // Windows cannot unlink the open lease handle; the root guard still excludes peers.
        for (String name : List.of("child", "launching", "lease", "owner")) Files.deleteIfExists(dir.resolve(name));
        Files.deleteIfExists(dir);
    }
    private static void checked(Path path, boolean directory) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther() || (directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("拒绝链接或非普通运行路径：" + path);
    }
    private static void warn(String message, Throwable error) { LOG.log(System.Logger.Level.WARNING, message, error); }
}
