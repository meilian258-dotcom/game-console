package cn.piq.fcarcade.runtime;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import cn.piq.fcarcade.runtime.RuntimeCatalog.Artifact;
import cn.piq.fcarcade.runtime.RuntimeCatalog.Component;
import cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId;

/**
 * Synchronous, offline-only service; call from a loading/UI worker, never a render/server tick.
 * Does not load libraries, launch processes, contact a server, or inspect ROM/BIOS/save folders.
 * The caller must prohibit installation while any emulator is starting/running/stopping.
 */
public final class RuntimeInstaller {
    public enum FileState { READY, MISSING, CONFLICT, UNSAFE }
    public enum PackState { MISSING, AVAILABLE, INVALID, UNSAFE }
    public enum Outcome { READY, AVAILABLE, INSTALLED, BLOCKED, CANCELLED, FAILED, BUSY }
    public enum Phase { CHECKING_FILES, CHECKING_PACK, STAGING, INSTALLING, ROLLING_BACK, DONE }
    public record FileStatus(String relativePath, FileState state, String detail) {}
    public record RuntimeStatus(RuntimeId id, FileState state, String summary, List<FileStatus> files) {
        public RuntimeStatus { files = List.copyOf(files); }
    }
    public record Progress(Phase phase, long completedBytes, long totalBytes, String message) {}
    public record Report(Outcome outcome, String summary, List<RuntimeStatus> runtimes, PackState pack,
                         List<String> details, int installed, int skipped) {
        public Report { runtimes = List.copyOf(runtimes); details = List.copyOf(details); }
        public boolean ready() { return outcome == Outcome.READY || outcome == Outcome.INSTALLED; }
    }

    private static final String PACK = "piq-runtime-packs/piq-runtime-pack-v1.zip";
    private static final String LOCK = "piq-runtime-packs/.piq-runtime-install.lock";
    private static final String BUNDLED_PREFIX = "native-runtime/win-x64-v1/";
    private static final BundleRegistry BUNDLES = new BundleRegistry();
    private final Path root;
    private final List<Component> catalog;
    private final List<Artifact> artifacts;
    private final List<Artifact> offlineArtifacts;
    private final Map<String, BundledSource> bundledFiles;
    private final boolean supported;

    /** The loader supplies its already loaded outer JAR, never install targets, hashes, or URLs. */
    public static void registerBundledArcade(Path archive) {
        BUNDLES.register(archive, EnumSet.of(RuntimeId.MAME, RuntimeId.NEOGEO_SNAPSHOT));
    }

    /** Fixed GBA catalog only: addons cannot provide destination paths, hashes or download URLs. */
    public static void registerBundledGba(Path archive) {
        BUNDLES.register(archive, EnumSet.of(RuntimeId.GBA));
    }

    /** Per-component ownership; either addon may be registered first without replacing the other. */
    static final class BundleRegistry {
        private final Map<RuntimeId, Path> owners = new LinkedHashMap<>();
        private volatile Map<RuntimeId, BundledSource> sources = Map.of();
        synchronized void register(Path archive, Set<RuntimeId> ids) {
            Path owner = archivePath(archive);
            for (var id : ids) {
                Path prior = owners.get(id);
                if (prior != null && !prior.equals(owner))
                    throw new IllegalStateException("Another runtime provider is already registered: " + id);
            }
            var merged = new LinkedHashMap<>(sources);
            for (var id : ids) if (!owners.containsKey(id)) {
                owners.put(id, owner);
                merged.put(id, bundledArchive(owner));
            }
            sources = Map.copyOf(merged);
        }
        Map<RuntimeId, BundledSource> snapshot() { return sources; }
    }

    private static Path archivePath(Path archive) {
        Objects.requireNonNull(archive);
        if (archive.getFileSystem() != FileSystems.getDefault())
            throw new IllegalArgumentException("内置运行库需要 mods 中的普通 JAR 文件");
        return archive.toAbsolutePath().normalize();
    }

    // Also used by inert fixture tests. Class/UnionFS resource streams can buffer the whole
    // 372 MB DLL before returning, so open the actual outer JAR with ZipFile instead.
    static BundledSource bundledArchive(Path archive) {
        Path owner = archivePath(archive);
        return artifact -> {
            RuntimePaths paths = new RuntimePaths(owner.getParent());
            BasicFileAttributes before = paths.check(owner, false, false);
            ZipFile zip = new ZipFile(owner.toFile());
            try {
                paths.unchanged(owner, before);
                String name = BUNDLED_PREFIX + artifact.relativePath();
                var entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() != artifact.bytes()
                        || zip.stream().filter(e -> e.getName().equals(name)).count() != 1)
                    throw new IOException("JAR 内置资源缺失、重复或大小不符：" + artifact.relativePath());
                return new FilterInputStream(zip.getInputStream(entry)) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (closed) return;
                        closed = true;
                        try (zip) { super.close(); }
                        finally { paths.unchanged(owner, before); }
                    }
                };
            } catch (IOException | RuntimeException failure) {
                try { zip.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        };
    }

    public static Set<RuntimeId> bundledRuntimeIds() { return BUNDLES.snapshot().keySet(); }

    @FunctionalInterface
    interface BundledSource { InputStream open(Artifact artifact) throws IOException; }

    public RuntimeInstaller(Path gameRoot) {
        this(cn.piq.retro.storage.ConsoleStorage.root(gameRoot), RuntimeCatalog.standard(), supportedPlatform(), BUNDLES.snapshot());
    }

    // Package-private for small inert fixture tests; production always uses the pinned catalog.
    RuntimeInstaller(Path gameRoot, List<Component> catalog) { this(gameRoot, catalog, true); }
    RuntimeInstaller(Path gameRoot, List<Component> catalog, boolean supported) {
        this(gameRoot, catalog, supported, Map.of());
    }
    RuntimeInstaller(Path gameRoot, List<Component> catalog, boolean supported, Map<RuntimeId, BundledSource> bundled) {
        root = Objects.requireNonNull(gameRoot).toAbsolutePath().normalize();
        this.supported = supported;
        this.catalog = List.copyOf(catalog);
        artifacts = this.catalog.stream().flatMap(c -> c.files().stream()).toList();
        offlineArtifacts = this.catalog.equals(RuntimeCatalog.standard()) ? RuntimeCatalog.originalOfflinePack() : artifacts;
        if (artifacts.isEmpty() || artifacts.stream().map(Artifact::relativePath).distinct().count() != artifacts.size()
                || this.catalog.stream().map(Component::id).distinct().count() != this.catalog.size())
            throw new IllegalArgumentException("Invalid runtime catalog");
        var sources = new LinkedHashMap<String, BundledSource>();
        for (var component : this.catalog) {
            var source = bundled.get(component.id());
            if (source != null) for (var artifact : component.files()) sources.put(artifact.relativePath(), source);
        }
        bundledFiles = Map.copyOf(sources);
    }

    public Path offlinePack() { return root.resolve(PACK); }
    public static boolean supportedPlatform() {
        return System.getProperty("os.name", "").startsWith("Windows")
                && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
    }

    /** Hash-check files. AVAILABLE means sources exist, not that uninstalled contents are trusted. */
    public Report inspect(Set<RuntimeId> selected, BooleanSupplier cancelled, Consumer<Progress> progress) {
        Work work = new Work(selected, cancelled, progress);
        if (work.files.isEmpty()) return work.report(Outcome.READY, "所选模组无须外置运行库", 0);
        if (!supports(work)) return unsupported(work);
        try {
            work.paths = new RuntimePaths(root);
            work.statuses = scan(work);
            work.pack = checkPack(work);
            return inspected(work);
        } catch (Cancelled stop) { return work.report(Outcome.CANCELLED, "已取消检查", 0); }
        catch (IOException | RuntimeException failure) { return failed(work, failure, "无法安全检查运行环境"); }
    }

    public Report install(Set<RuntimeId> selected, BooleanSupplier cancelled, Consumer<Progress> progress) {
        Work work = new Work(selected, cancelled, progress);
        if (work.files.isEmpty()) return work.report(Outcome.READY, "所选模组无须外置运行库", 0);
        if (!supports(work)) return unsupported(work);
        List<Owned> owned = new ArrayList<>();
        List<Owned> createdDirectories = new ArrayList<>();
        List<Owned> committed = new ArrayList<>();
        try {
            work.paths = new RuntimePaths(root);
            work.statuses = scan(work);
            if (hasConflict(work)) return work.report(Outcome.BLOCKED, "已有异版本或不安全路径；已保留原文件", 0);
            if (allReady(work)) return work.report(Outcome.READY, "所选运行环境已就绪，无需安装", 0);
            work.pack = checkPack(work);
            if (work.pack != PackState.AVAILABLE) return work.report(Outcome.BLOCKED,
                    work.pack == PackState.MISSING ? "缺少安装来源；未内置的组件需配套离线包" : "运行库安装来源校验未通过", 0);
            Path lockPath = work.paths.resolve(LOCK);
            // Embedded-only first install has no pre-existing offline-pack directory.
            ensureDirectories(work, lockPath.getParent(), createdDirectories);
            BasicFileAttributes lockBefore = work.paths.check(lockPath, false, true);
            if (lockBefore != null && lockBefore.size() != 0) throw new IOException("安装锁文件不是空文件，已保留");
            // Deliberately persistent zero-byte lock: unlinking it permits concurrent lock inodes.
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                work.paths.check(lockPath, false, false);
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException busy) { return work.report(Outcome.BUSY, "另一个运行库安装正在进行", 0); }
                if (lock == null) return work.report(Outcome.BUSY, "另一个运行库安装正在进行", 0);
                try (lock) {
                    // Re-read after acquiring the cross-process lock; never trust the UI's earlier report.
                    work.statuses = scan(work);
                    if (hasConflict(work)) return work.report(Outcome.BLOCKED, "安装前发现异版本文件；未覆盖", 0);
                    List<Artifact> missing = missing(work);
                    if (missing.isEmpty()) return work.report(Outcome.READY, "所选运行环境已就绪，无需安装", 0);
                    Path stage = work.paths.resolve("piq-runtime-packs/.install-" + UUID.randomUUID());
                    createDirectory(work, stage, createdDirectories);
                    Map<Artifact, Path> staged = stage(work, stage, missing, owned);
                    // All missing bytes are staged and SHA checked before any executable is installed.
                    work.statuses = scan(work);
                    if (hasConflict(work)) throw new IOException("校验期间目标发生变化；未覆盖原文件");
                    long total = missing.stream().mapToLong(Artifact::bytes).sum(), completed = 0;
                    for (var item : staged.entrySet()) {
                        work.checkCancelled();
                        Artifact artifact = item.getKey(); Path source = item.getValue();
                        Path target = work.paths.resolve(artifact.relativePath());
                        ensureDirectories(work, target.getParent(), createdDirectories);
                        BasicFileAttributes current = work.paths.check(target, false, true);
                        if (current != null) {
                            if (!matches(work, target, artifact, Phase.INSTALLING, completed, total))
                                throw new IOException("目标已有其他版本，已保留：" + artifact.relativePath());
                        } else {
                            BasicFileAttributes stagedIdentity = work.paths.check(source, false, false);
                            // Atomic CREATE_NEW publication on the same volume; unlike ATOMIC_MOVE,
                            // createLink cannot replace an existing destination in a racing process.
                            try { Files.createLink(target, source); }
                            catch (UnsupportedOperationException unsupported) { throw new IOException("当前磁盘不支持安全的新文件发布，请使用 NTFS 游戏目录", unsupported); }
                            // The new target must have the source identity. Never adopt a foreign
                            // replacement that appears after createLink and then delete it on rollback.
                            Owned publication = new Owned(target, RuntimePaths.identity(stagedIdentity), false, source);
                            committed.add(publication);
                            work.paths.check(target, false, false);
                            if (!Files.isSameFile(source, target))
                                throw new IOException("目标在安装期间被其他程序替换，已保留：" + artifact.relativePath());
                            if (!matches(work, target, artifact, Phase.INSTALLING, completed, total))
                                throw new IOException("安装后的文件校验失败：" + artifact.relativePath());
                        }
                        completed += artifact.bytes();
                        work.emit(Phase.INSTALLING, completed, total, "已安装校验 " + artifact.relativePath());
                    }
                    work.checkCancelled();
                    work.statuses = scan(work);
                    if (!allReady(work)) throw new IOException("安装后的最终校验未通过");
                    work.emit(Phase.DONE, total, total, "运行环境安装完成");
                    int count = committed.size(); committed.clear(); // Commit: no rollback of successful files.
                    cleanup(work, owned, "清理本次临时文件");
                    cleanup(work, createdDirectories, "清理本次空目录");
                    return work.report(Outcome.INSTALLED, "运行环境安装完成", count);
                } catch (Cancelled stop) {
                    cleanup(work, committed, "已回滚本次新增运行库");
                    cleanup(work, owned, "清理本次临时文件");
                    cleanup(work, createdDirectories, "清理本次空目录");
                    return work.report(Outcome.CANCELLED, "已取消安装；原文件未覆盖", 0);
                } catch (IOException | RuntimeException failure) {
                    cleanup(work, committed, "已回滚本次新增运行库");
                    cleanup(work, owned, "清理本次临时文件");
                    cleanup(work, createdDirectories, "清理本次空目录");
                    return failed(work, failure, "安装未完成；原文件未覆盖");
                } finally {
                    cleanup(work, owned, "清理本次临时文件");
                    cleanup(work, createdDirectories, "清理本次空目录");
                }
            }
        } catch (Cancelled stop) { return work.report(Outcome.CANCELLED, "已取消安装", 0); }
        catch (IOException | RuntimeException failure) { return failed(work, failure, "无法安全安装运行环境"); }
        finally {
            cleanup(work, owned, "清理本次临时文件");
            cleanup(work, createdDirectories, "清理本次空目录");
        }
    }

    private Report inspected(Work w) {
        if (hasConflict(w)) return w.report(Outcome.BLOCKED, "已有异版本或不安全路径；不会自动覆盖", 0);
        if (allReady(w)) return w.report(Outcome.READY, "所选运行环境已就绪", 0);
        return w.report(w.pack == PackState.AVAILABLE ? Outcome.AVAILABLE : Outcome.BLOCKED,
                w.pack == PackState.AVAILABLE ? "发现缺少的运行库，可从内置资源或离线包补齐"
                        : w.pack == PackState.MISSING ? "缺少运行库安装来源" : "运行库安装来源不安全或不完整", 0);
    }

    private boolean supports(Work w) {
        // The ordinary Arcade bridge specifically requires JVM amd64. Keep GBA's existing
        // platform rule, but never let the manual UI bypass bundled Arcade's startup gate.
        return supported && (bundledFiles.isEmpty()
                || !w.selected.contains(RuntimeId.MAME) && !w.selected.contains(RuntimeId.NEOGEO_SNAPSHOT)
                || "amd64".equals(System.getProperty("os.arch", "")));
    }

    private Report unsupported(Work w) {
        w.details.add("当前离线运行库仅适用于 Windows x64；系统=" + System.getProperty("os.name", "未知")
                + "，架构=" + System.getProperty("os.arch", "未知") + "；内置街机要求 JVM os.arch=amd64");
        return w.report(Outcome.BLOCKED, "当前运行库仅支持 Windows x64", 0);
    }

    private PackState checkPack(Work w) throws Cancelled {
        w.checkCancelled(); w.emit(Phase.CHECKING_PACK, 0, 0, "检查内置资源及离线安装来源");
        boolean needsOffline = bundledFiles.isEmpty();
        for (Artifact artifact : missing(w)) {
            w.checkCancelled();
            BundledSource source = bundledFiles.get(artifact.relativePath());
            if (source == null) {
                if(!offlineArtifacts.contains(artifact)) {
                    w.details.add("新版本化街机 helper 需要 Native 0.1.1 内置来源；旧离线包和已有文件保持不变");
                    return PackState.INVALID;
                }
                needsOffline = true; continue;
            }
            try (InputStream input = source.open(artifact)) {
                if (input == null) throw new IOException("内置运行库资源不可读：" + artifact.relativePath());
                w.details.add("内置来源（安装时校验 SHA-256）：" + artifact.relativePath());
            } catch (IOException failure) {
                w.details.add(failure.getMessage());
                if(artifact.relativePath().equals("piq-native-arcade/runtime/piq-native-helper-v4.jar"))
                    w.details.add("请安装配套 Native 0.1.1；旧离线包不含 v4 helper，原文件不会覆盖");
                return PackState.INVALID;
            }
        }
        if (!needsOffline) return PackState.AVAILABLE;
        try {
            Path pack = w.paths.resolve(PACK);
            BasicFileAttributes before = w.paths.check(pack, false, true);
            if (before == null) return PackState.MISSING;
            try { RuntimePack.validate(pack, offlineArtifacts); w.paths.unchanged(pack, before); return PackState.AVAILABLE; }
            catch (IOException bad) { w.details.add(bad.getMessage()); return PackState.INVALID; }
        } catch (IOException unsafe) { w.details.add(unsafe.getMessage()); return PackState.UNSAFE; }
    }

    private List<RuntimeStatus> scan(Work w) throws Cancelled {
        List<RuntimeStatus> result = new ArrayList<>();
        // Only bytes actually present and eligible for hashing count as byte progress.
        // Missing catalog entries are checked without pretending to transfer their nominal size.
        long total = 0, done = 0;
        for (Artifact file : w.files) {
            w.checkCancelled();
            try {
                BasicFileAttributes a = w.paths.check(w.paths.resolve(file.relativePath()), false, true);
                if (a != null && a.size() == file.bytes()) total += file.bytes();
            } catch (IOException ignored) { /* The per-file pass below records the detailed unsafe status. */ }
        }
        for (Component component : catalog) {
            if (!w.selected.contains(component.id())) continue;
            List<FileStatus> files = new ArrayList<>();
            for (Artifact file : component.files()) {
                w.checkCancelled(); w.transferredBytes = 0;
                FileState state; String detail;
                try {
                    Path path = w.paths.resolve(file.relativePath());
                    if (w.paths.check(path, false, true) == null) { state = FileState.MISSING; detail = "缺少文件"; }
                    else if (matches(w, path, file, Phase.CHECKING_FILES, done, total)) { state = FileState.READY; detail = "大小和 SHA-256 均匹配"; }
                    else { state = FileState.CONFLICT; detail = "已有文件与配套版本不符，保留且不覆盖"; }
                } catch (IOException unsafe) { state = FileState.UNSAFE; detail = unsafe.getMessage(); }
                files.add(new FileStatus(file.relativePath(), state, detail)); done += w.transferredBytes;
                w.emit(Phase.CHECKING_FILES, done, Math.max(total, done), "已检查 " + file.relativePath());
            }
            FileState state = files.stream().anyMatch(f -> f.state() == FileState.UNSAFE) ? FileState.UNSAFE
                    : files.stream().anyMatch(f -> f.state() == FileState.CONFLICT) ? FileState.CONFLICT
                    : files.stream().anyMatch(f -> f.state() == FileState.MISSING) ? FileState.MISSING : FileState.READY;
            result.add(new RuntimeStatus(component.id(), state, component.title() + "：" + switch (state) {
                case READY -> "已就绪"; case MISSING -> "未安装完整"; case CONFLICT -> "版本不匹配"; case UNSAFE -> "路径不可安全访问";
            }, files));
        }
        return List.copyOf(result);
    }

    private Map<Artifact, Path> stage(Work w, Path directory, List<Artifact> missing, List<Owned> owned) throws IOException, Cancelled {
        boolean needsOffline = missing.stream().anyMatch(a -> !bundledFiles.containsKey(a.relativePath()));
        Path pack = needsOffline ? w.paths.resolve(PACK) : null;
        BasicFileAttributes before = needsOffline ? w.paths.check(pack, false, false) : null;
        if (needsOffline) RuntimePack.validate(pack, offlineArtifacts);
        Map<Artifact, Path> result = new LinkedHashMap<>();
        long total = missing.stream().mapToLong(Artifact::bytes).sum(), done = 0;
        try (ZipFile zip = needsOffline ? new ZipFile(pack.toFile()) : null) {
            for (Artifact artifact : missing) {
                w.checkCancelled(); w.paths.check(directory, true, false);
                Path target = directory.resolve("file-" + result.size() + ".part");
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    owned.add(own(w, target));
                    try (InputStream input = openArtifact(artifact, zip)) {
                        if (!transfer(w, input, output, artifact, Phase.STAGING, done, total))
                            throw new IOException("运行库内容 SHA-256 不匹配：" + artifact.relativePath());
                    }
                }
                // Force fully staged bytes before publishing a directory entry.
                try (FileChannel file = FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
                result.put(artifact, target); done += artifact.bytes();
            }
        }
        if (needsOffline) w.paths.unchanged(pack, before);
        return result;
    }

    private InputStream openArtifact(Artifact artifact, ZipFile zip) throws IOException {
        BundledSource source = bundledFiles.get(artifact.relativePath());
        if (source != null) {
            InputStream input = source.open(artifact);
            if (input == null) throw new IOException("内置运行库资源不可读：" + artifact.relativePath());
            return input;
        }
        var entry = zip == null ? null : zip.getEntry(artifact.relativePath());
        if (entry == null || entry.getSize() != artifact.bytes()) throw new IOException("离线安装包文件不完整");
        return zip.getInputStream(entry);
    }

    private boolean matches(Work w, Path path, Artifact a, Phase phase, long base, long total) throws IOException, Cancelled {
        BasicFileAttributes before = w.paths.check(path, false, false);
        if (before.size() != a.bytes()) return false;
        boolean valid;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { valid = transfer(w, input, null, a, phase, base, total); }
        w.paths.unchanged(path, before);
        return valid;
    }

    private boolean transfer(Work w, InputStream input, OutputStream output, Artifact a, Phase phase, long base, long total) throws IOException, Cancelled {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[65536]; long count = 0; w.transferredBytes = 0;
        while (true) {
            w.checkCancelled(); int read = input.read(buffer, 0, (int) Math.min(buffer.length, a.bytes() - count + 1));
            if (read < 0) break;
            if (read == 0) continue;
            count += read; w.transferredBytes = count;
            if (count > a.bytes()) throw new IOException("运行库文件解压大小超过固定上限：" + a.relativePath());
            digest.update(buffer, 0, read); if (output != null) output.write(buffer, 0, read);
            w.emit(phase, base + count, Math.max(total, base + count), (phase == Phase.STAGING ? "解压并校验 " : "校验 ") + a.relativePath());
        }
        return count == a.bytes() && HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(a.sha256());
    }

    private void ensureDirectories(Work w, Path directory, List<Owned> created) throws IOException {
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (w.paths.check(current, true, true) == null) createDirectory(w, current, created);
        }
    }
    private void createDirectory(Work w, Path path, List<Owned> created) throws IOException {
        w.paths.check(path.getParent(), true, false); Files.createDirectory(path);
        BasicFileAttributes a = w.paths.check(path, true, false);
        created.add(new Owned(path, RuntimePaths.identity(a), true, null));
    }
    private Owned own(Work w, Path path) throws IOException { return new Owned(path, RuntimePaths.identity(w.paths.check(path, false, false)), false, null); }
    private record Owned(Path path, Object identity, boolean directory, Path witness) {}

    private void cleanup(Work w, List<Owned> files, String reason) {
        for (int i = files.size() - 1; i >= 0; i--) {
            Owned file = files.get(i);
            try {
                BasicFileAttributes now = w.paths.check(file.path(), file.directory(), true);
                boolean same = now != null && Objects.equals(RuntimePaths.identity(now), file.identity());
                if (same && file.witness() != null) {
                    w.paths.check(file.witness(), false, false);
                    same = Files.isSameFile(file.path(), file.witness());
                }
                if (same) Files.delete(file.path());
            } catch (DirectoryNotEmptyException expected) { /* A successful runtime directory is deliberately retained. */ }
            catch (IOException | RuntimeException retained) { w.details.add(reason + "时保留未能安全移除的路径：" + file.path()); }
        }
        files.clear();
    }

    private boolean hasConflict(Work w) { return w.statuses.stream().anyMatch(s -> s.state() == FileState.CONFLICT || s.state() == FileState.UNSAFE); }
    private boolean allReady(Work w) { return !w.statuses.isEmpty() && w.statuses.stream().allMatch(s -> s.state() == FileState.READY); }
    private List<Artifact> missing(Work w) {
        Set<String> paths = w.statuses.stream().flatMap(s -> s.files().stream()).filter(f -> f.state() == FileState.MISSING)
                .map(FileStatus::relativePath).collect(java.util.stream.Collectors.toSet());
        return w.files.stream().filter(f -> paths.contains(f.relativePath())).toList();
    }
    private Report failed(Work w, Exception failure, String summary) { w.details.add(failure.getClass().getSimpleName() + ": " + failure.getMessage()); return w.report(Outcome.FAILED, summary, 0); }

    private final class Work {
        final Set<RuntimeId> selected; final List<Artifact> files; final BooleanSupplier cancelled; final Consumer<Progress> progress;
        long transferredBytes;
        final List<String> details = new ArrayList<>(); RuntimePaths paths; List<RuntimeStatus> statuses = List.of(); PackState pack = PackState.MISSING;
        Work(Set<RuntimeId> selected, BooleanSupplier cancelled, Consumer<Progress> progress) {
            this.selected = selected.isEmpty() ? Set.of() : EnumSet.copyOf(selected);
            this.cancelled = Objects.requireNonNull(cancelled); this.progress = Objects.requireNonNull(progress);
            files = catalog.stream().filter(c -> this.selected.contains(c.id())).flatMap(c -> c.files().stream()).toList();
        }
        void checkCancelled() throws Cancelled { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Cancelled(); }
        void emit(Phase phase, long completed, long total, String message) { progress.accept(new Progress(phase, completed, total, message)); }
        Report report(Outcome outcome, String summary, int installed) {
            int ready = (int) statuses.stream().flatMap(s -> s.files().stream()).filter(f -> f.state() == FileState.READY).count();
            List<String> full = new ArrayList<>(details);
            statuses.stream().flatMap(s -> s.files().stream()).filter(f -> f.state() != FileState.READY)
                    .forEach(f -> full.add(f.relativePath() + "：" + f.detail()));
            return new Report(outcome, summary, statuses, pack, full, installed, Math.max(0, ready - installed));
        }
    }
    private static final class Cancelled extends Exception {}
}
