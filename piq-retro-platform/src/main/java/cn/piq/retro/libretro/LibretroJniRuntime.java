// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Opt-in in-process owner-thread runtime. Native faults can terminate the JVM. Deadlines report
 * failure; they NEVER kill a native thread, unload its library or release its active slot.
 * No server/save ownership/netplay behavior is introduced here. Persistent files, when requested
 * by a trusted local adapter, must belong to that adapter's isolated trial save namespace.
 */
public final class LibretroJniRuntime implements LibretroRuntime {
    public static final int WGL_COMPAT = 1, POINTER = 2, MOUSE = 4, KEYBOARD = 8, MESEN_GUN = 16, NO_GAME = 32, LEGACY_INLINE_OPTIONS = 64;
    private static final long MIB = 1024L * 1024;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final Thread owner = Thread.currentThread();
    private final LibretroProfile profile;
    private final Class<?> resourceOwner;
    private final int features;
    private RuntimeWorkspace workspace;
    private AutoCloseable nativePin;
    private FileChannel saveChannel;
    private FileLock saveLock;
    private long token;
    private boolean closed, claimed, nativeOpenAttempted;
    private volatile String timeout = "";
    private LibretroProcess.Info info;
    private String version;
    private byte[] identity;
    private int rotation;
    private int saveCapabilities;
    private final int[] metadata = new int[11];
    private final double[] timing = new double[3];
    private ByteBuffer video, audio;

    public LibretroJniRuntime(LibretroProfile profile, Class<?> resourceOwner) {
        this(profile, resourceOwner, profile.mesenGun() ? MESEN_GUN : 0);
    }
    /** Feature flags are a trusted adapter declaration, never accepted from content/server metadata. */
    public LibretroJniRuntime(LibretroProfile profile, Class<?> resourceOwner, int features) {
        this.profile = Objects.requireNonNull(profile); this.resourceOwner = Objects.requireNonNull(resourceOwner);
        if ((features & ~127) != 0 || profile.mesenGun() != ((features & MESEN_GUN) != 0))
            throw new IllegalArgumentException("JNI feature declaration");
        this.features = features;
    }
    static boolean isBusy() { return ACTIVE.get(); }
    @Override public LibretroRuntimes.Backend backend() { return LibretroRuntimes.Backend.JNI_TRIAL; }
    @Override public Set<Capability> capabilities() {
        var set = EnumSet.of(Capability.SOFTWARE_VIDEO, Capability.DIGITAL_PADS);
        if ((saveCapabilities & 1) != 0) set.add(Capability.STATE);
        if ((saveCapabilities & 2) != 0) set.add(Capability.SAVE_MEMORY);
        if ((features & WGL_COMPAT) != 0) set.add(Capability.OPENGL_COMPAT_VIDEO);
        if ((features & POINTER) != 0) set.add(Capability.POINTER);
        if ((features & MOUSE) != 0) set.add(Capability.MOUSE);
        if ((features & KEYBOARD) != 0) set.add(Capability.KEYBOARD);
        if ((features & MESEN_GUN) != 0) set.add(Capability.LIGHT_GUN);
        return Collections.unmodifiableSet(set);
    }
    @Override public LibretroProcess.Info load(byte[] content) {
        check();
        if (content == null || content.length < 1 || content.length > 64 * MIB || (features & NO_GAME) != 0)
            throw new IllegalArgumentException("Content size/type");
        content = content.clone();
        try {
            Path root = prepare(); Path main = root.resolve("content." + profile.extension());
            Files.write(main, content, StandardOpenOption.CREATE_NEW);
            return open(main, root, null, hash(content));
        } catch (IOException | RuntimeException | LinkageError e) { throw fail(e); }
    }
    /**
     * Local adapter-only content staging for named ZIP/BIOS or no-game system assets.
     * Up to 16 files, 64 MiB/file, 128 MiB total; sources are copied, never edited. This does not
     * enlarge the server's existing content manifest or grant permission to share local files.
     * mainName must name a staged file, or be empty ONLY for an explicitly declared NO_GAME core.
     */
    public LibretroProcess.Info loadFiles(String mainName, Map<String, Path> files, Path trialSaveDirectory) {
        check(); Objects.requireNonNull(mainName); files = new TreeMap<>(files);
        if (files.isEmpty() || files.size() > 16 || ((features & NO_GAME) == 0 && !files.containsKey(mainName))
                || ((features & NO_GAME) != 0 && !mainName.isEmpty())) throw new IllegalArgumentException("Content manifest");
        Set<String> names = new HashSet<>();
        for (String name : files.keySet()) {
            validateRelative(name);
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate staged file name");
        }
        try {
            Path root = prepare(); long total = 0; var hashes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(hashes)) {
                for (var entry : files.entrySet()) {
                    Path source = entry.getValue().toAbsolutePath().normalize(); noLinks(source, false);
                    var before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    long size = before.size(); total += size;
                    if (size < 1 || size > 64 * MIB || total > 128 * MIB) throw new IOException("Content budget exceeded");
                    Path target = root.resolve(entry.getKey()).normalize();
                    if (!target.startsWith(root)) throw new IOException("Content escaped staging");
                    Files.createDirectories(target.getParent());
                    MessageDigest digest = digest();
                    try (var in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                         var to = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65536]; long copied = 0; int n;
                        while ((n = in.read(buffer)) != -1) {
                            copied += n; if (copied > size) throw new IOException("Content changed while copying");
                            digest.update(buffer, 0, n); to.write(buffer, 0, n);
                        }
                        if (copied != size) throw new IOException("Content changed while copying");
                    }
                    var after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!after.isRegularFile() || after.isSymbolicLink() || after.isOther() || after.size() != size
                            || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                        throw new IOException("Content changed while copying");
                    out.writeUTF(entry.getKey()); out.write(digest.digest());
                }
            }
            return open(mainName.isEmpty() ? null : root.resolve(mainName), root, trialSaveDirectory, hash(hashes.toByteArray()));
        } catch (IOException | RuntimeException | LinkageError e) { throw fail(e); }
    }
    private Path prepare() throws IOException {
        if (workspace != null || claimed) throw new IllegalStateException("JNI core already started");
        String reason = LibretroRuntimes.jniUnavailableReason(); if (!reason.isEmpty()) throw new IOException(reason);
        if (!ACTIVE.compareAndSet(false, true)) throw new IOException("已有 JNI 试验运行或尚未安全退出，请切回独立进程或重启客户端");
        claimed = true;
        workspace = RuntimeWorkspace.create("libretro", 448 * MIB);
        NativeLibretroBridge.load();
        var artifact = profile.cores().get("windows-x64");
        if (artifact == null) throw new IOException("No pinned Windows x64 core");
        NativeLibretroBridge.extract(resourceOwner, artifact.resource(), artifact.sha256(), workspace.directory().resolve("core.dll"), 256 * MIB);
        return Files.createDirectory(workspace.directory().resolve("content"));
    }
    private LibretroProcess.Info open(Path content, Path system, Path saves, byte[] contentHash) throws IOException {
        Path save = saves == null ? Files.createDirectory(workspace.directory().resolve("save")) : saves.toAbsolutePath().normalize();
        if (saves != null) {
            noLinks(save, true); Files.createDirectories(save); noLinks(save, true);
            saveChannel = FileChannel.open(save.resolve("piq-jni-session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { saveLock = saveChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("试验存档正在使用", e); }
            if (saveLock == null) throw new IOException("试验存档正在使用");
        }
        nativePin = workspace.pinNative();
        var options = new ArrayList<String>(); profile.options().forEach((k, v) -> { options.add(k); options.add(v); });
        try (var deadline = deadline(45, "初始化")) {
            nativeOpenAttempted = true;
            token = NativeLibretroBridge.open(workspace.directory().resolve("core.dll").toString(),
                    content == null ? "" : content.toString(), system.toString(), save.toString(), profile.name(), profile.fullPath(),
                    profile.devices().stream().mapToInt(Integer::intValue).toArray(), options.toArray(String[]::new), features);
            if (token == 0) throw new IOException("JNI core returned no session");
            NativeLibretroBridge.metadata(token, metadata, timing); updateMetadata();
            version = NativeLibretroBridge.coreVersion(token);
            saveCapabilities = NativeLibretroBridge.saveCapabilities(token);
            if (version == null || version.isBlank() || version.length() > 128) throw new IOException("Invalid core identity");
            video = ByteBuffer.allocateDirect(2048 * 2048 * 4).order(ByteOrder.LITTLE_ENDIAN);
            audio = ByteBuffer.allocateDirect(32768 * 2).order(ByteOrder.LITTLE_ENDIAN);
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("piq-libretro-jni-trial-save-v1"); out.write(contentHash);
                out.writeUTF(profile.name()); out.writeUTF(version); out.writeUTF(profile.cores().get("windows-x64").sha256().toLowerCase(Locale.ROOT));
                out.writeUTF(profile.extension()); out.writeBoolean(profile.fullPath()); out.writeInt(features);
                for (int device : profile.devices()) out.writeInt(device);
                for (String option : options) out.writeUTF(option);
            }
            identity = hash(bytes.toByteArray()); return info;
        }
    }
    @Override public LibretroProcess.Info info() { loaded(); return info; }
    @Override public String coreVersion() { loaded(); return version; }
    @Override public int rotation() { loaded(); return rotation; }
    @Override public byte[] persistenceIdentity() { loaded(); return identity.clone(); }
    @Override public LibretroProcess.Output run(List<LibretroProcess.Controls> frames, int outputMask) { return runWithMemory(frames, outputMask, -1); }
    @Override public LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> frames, int outputMask, int memoryId) {
        loaded();
        if (frames == null || frames.isEmpty() || frames.size() > 120 || (outputMask & ~3) != 0 || memoryId < -1 || memoryId > 3)
            throw new IllegalArgumentException("JNI frame batch");
        // Validate all frames before allowing any native state mutation.
        frames = List.copyOf(frames);
        for (var frame : frames) validate(frame);
        byte[] rgba = new byte[0]; short[] pcm = new short[0]; boolean duplicate = false;
        for (var frame : frames) {
            int[] input = neutralInput(); int[] pads = frame.pads(); System.arraycopy(pads, 0, input, 0, pads.length); input[12] = frame.gun();
            var result = step(input, new int[0], outputMask); rgba = result.rgba(); duplicate = result.duplicate();
            if (pcm.length + result.stereo().length > 524288) throw new IllegalStateException("JNI batch audio exceeds budget");
            int previous = pcm.length; pcm = Arrays.copyOf(pcm, previous + result.stereo().length);
            System.arraycopy(result.stereo(), 0, pcm, previous, result.stereo().length);
        }
        return new LibretroProcess.Output(info, duplicate, rgba, pcm, memoryId < 0 ? new byte[0] : memory(memoryId));
    }
    /** Advanced trusted local adapter input. Layout is documented in the JNI v1 guide; native validates every field. */
    public LibretroProcess.Output step(int[] input, int[] keyEvents, int outputMask) {
        loaded(); if ((outputMask & ~3) != 0 || input == null || input.length != 13 || keyEvents == null
                || keyEvents.length > 512 || keyEvents.length % 4 != 0) throw new IllegalArgumentException("JNI input layout");
        input = input.clone(); keyEvents = keyEvents.clone();
        try (var deadline = deadline(10, "运行")) {
            video.clear(); audio.clear(); NativeLibretroBridge.step(token, video, audio, input, keyEvents, metadata, timing); updateMetadata();
            if (metadata[9] != 0) throw new IOException("Core requested shutdown");
            if (metadata[6] != info.width() * info.height() * 4 || metadata[7] < 0 || metadata[7] > 32768 || (metadata[7] & 1) != 0)
                throw new IOException("JNI output length");
            byte[] pixels = new byte[(outputMask & LibretroProcess.VIDEO) == 0 ? 0 : metadata[6]];
            short[] pcm = new short[(outputMask & LibretroProcess.AUDIO) == 0 ? 0 : metadata[7]];
            video.get(pixels); audio.asShortBuffer().get(pcm);
            return new LibretroProcess.Output(info, metadata[8] != 0, pixels, pcm, new byte[0]);
        } catch (IOException | LinkageError e) { throw fail(e); }
    }
    public static int[] neutralInput() { int[] value = new int[13]; value[4] = -1; value[5] = value[6] = -32768; value[11] = -1; return value; }
    private void validate(LibretroProcess.Controls frame) {
        if (frame == null || frame.pads().length != profile.devices().size()) throw new IllegalArgumentException("Controller count");
        for (int pad : frame.pads()) if ((pad & ~65535) != 0) throw new IllegalArgumentException("RetroPad mask");
        int gun = frame.gun();
        if ((!profile.mesenGun() && gun != 0) || (gun & ~0x3ffff) != 0 || profile.mesenGun()
                && (((gun >>> 8) & 255) >= 240 || ((gun & 65536) != 0 && (gun & 65535) != 0))) throw new IllegalArgumentException("Gun input");
    }
    private void updateMetadata() throws IOException {
        if (metadata[0] < 1 || metadata[1] < 1 || metadata[2] > 2048 || metadata[3] > 2048
                || metadata[5] < 0 || metadata[5] > 3) throw new IOException("JNI geometry exceeds contract");
        try { info = new LibretroProcess.Info(metadata[0], metadata[1], metadata[2], metadata[3], (float) timing[0], timing[1], timing[2], metadata[4]); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid JNI AV metadata", e); }
        rotation = metadata[5];
    }
    @Override public byte[] serialize() {
        loaded();
        if ((saveCapabilities & 1) == 0) return null;
        return invoke(() -> NativeLibretroBridge.serialize(token), "保存状态");
    }
    @Override public void restore(byte[] state) {
        loaded(); if (state == null || state.length < 1 || state.length > 16 * MIB) throw new IllegalArgumentException("State budget");
        if ((saveCapabilities & 1) == 0) throw new UnsupportedOperationException("Core does not support state restore");
        byte[] copy = state.clone(); invoke(() -> { NativeLibretroBridge.restore(token, copy); return null; }, "恢复状态");
    }
    @Override public LibretroProcess.Info reset() {
        loaded(); return invoke(() -> { NativeLibretroBridge.reset(token); NativeLibretroBridge.metadata(token, metadata, timing); updateMetadata(); return info; }, "重置");
    }
    @Override public byte[] memory(int id) {
        loaded(); if (id < 0 || id > 3) throw new IllegalArgumentException("Memory region");
        return invoke(() -> NativeLibretroBridge.memory(token, id), "读取存储");
    }
    @Override public LibretroSaveMemory saveMemory() { loaded(); return new LibretroSaveMemory(memory(0), memory(1)); }
    @Override public void restoreSaveMemory(LibretroSaveMemory memory) {
        loaded(); Objects.requireNonNull(memory);
        invoke(() -> { NativeLibretroBridge.restoreMemory(token, memory.ram(), memory.rtc()); return null; }, "恢复存储");
    }
    private interface IoCall<T> { T run() throws IOException; }
    private <T> T invoke(IoCall<T> action, String name) {
        try (var deadline = deadline(10, name)) { return action.run(); }
        catch (IOException | LinkageError e) { throw fail(e); }
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("JNI core accessed outside owner thread");
        if (closed) throw new IllegalStateException("JNI core closed");
        if (!timeout.isEmpty()) throw new IllegalStateException(timeout);
    }
    private void loaded() { check(); if (token == 0 || info == null) throw new IllegalStateException("JNI core not loaded"); }
    public String timeoutError() { return timeout; }
    @Override public String diagnosticError() { return timeout; }
    private IllegalStateException fail(Throwable error) {
        try { close(); } catch (RuntimeException | LinkageError cleanup) { error.addSuppressed(cleanup); }
        return new IllegalStateException("通用 JNI 试验失败：" + error.getMessage(), error);
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("JNI close outside owner thread");
        if (closed) return;
        // If this call hangs, the pin, save lock and ACTIVE remain held. The watchdog only reports.
        try (var deadline = deadline(12, "关闭")) {
            if (token == 0 && nativeOpenAttempted && NativeLibretroBridge.reservationHeld())
                throw new IOException("JNI初始化清理未确认，保留试验工作区和运行名额；请重启客户端");
            if (token != 0) { NativeLibretroBridge.close(token); token = 0; }
        } catch (IOException | LinkageError e) { throw new IllegalStateException("JNI退出未确认，请保存世界后重启客户端", e); }
        closed = true;
        Exception failure = null;
        for (AutoCloseable resource : new AutoCloseable[]{saveLock, saveChannel, nativePin, workspace}) if (resource != null) {
            try { resource.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (claimed) { ACTIVE.set(false); claimed = false; }
        if (failure != null) throw new IllegalStateException("JNI已关闭，但试验工作区清理失败", failure);
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, r -> { var t = new Thread(r, "PIQ-JNI-deadlines"); t.setDaemon(true); return t; });
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    private Deadline deadline(int seconds, String operation) { return new Deadline(seconds, operation); }
    private final class Deadline implements AutoCloseable {
        private final AtomicInteger state = new AtomicInteger(); private final ScheduledFuture<?> future;
        Deadline(int seconds, String operation) {
            future = DEADLINES.schedule(() -> { if (state.compareAndSet(0, 2)) timeout = "JNI" + operation + "超时；无法安全强杀，请重启客户端"; }, seconds, TimeUnit.SECONDS);
        }
        @Override public void close() throws IOException { future.cancel(false); if (!state.compareAndSet(0, 1)) throw new IOException(timeout); }
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private static byte[] hash(byte[] bytes) { return digest().digest(bytes); }
    private static void validateRelative(String name) {
        if (name == null || name.length() > 128 || !name.matches("[A-Za-z0-9_ .!()\\-]+(?:/[A-Za-z0-9_ .!()\\-]+)*")
                || Arrays.stream(name.split("/", -1)).anyMatch(s -> s.equals(".") || s.equals("..") || s.endsWith(".") || s.endsWith(" ")
                    || s.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")))
            throw new IllegalArgumentException("Unsafe staged file name");
    }
    private static void noLinks(Path path, boolean directory) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            var stat = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (stat.isSymbolicLink() || stat.isOther() || (current.equals(path) && !directory ? !stat.isRegularFile() : !stat.isDirectory()))
                throw new IOException("JNI路径不能经过链接或错误文件类型");
        }
    }
}
