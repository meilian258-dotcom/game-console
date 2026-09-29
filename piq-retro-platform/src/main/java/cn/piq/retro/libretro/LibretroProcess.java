// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchronous, bounded software-libretro transport, used ONLY on an owning core worker.
 * Native code is isolated from the game JVM; this is not an operating-system sandbox.
 * No Minecraft, save-policy, ROM distribution, player authorization or netplay logic lives here.
 */
public final class LibretroProcess implements LibretroRuntime {
    public static final int VIDEO = 1, AUDIO = 2;
    private static final int MAGIC = 0x504c5232, MAX_STATE = 16 * 1024 * 1024;
    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final ScheduledThreadPoolExecutor TIMERS = timers();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> CHILDREN.forEach(Process::destroyForcibly), "libretro-generic-shutdown"));
    }
    private static ScheduledThreadPoolExecutor timers() {
        var value = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "libretro-generic-deadline"); thread.setDaemon(true); return thread;
        });
        value.setRemoveOnCancelPolicy(true); return value;
    }
    public record Info(int width, int height, int maxWidth, int maxHeight, float aspect,
                       double fps, double sampleRate, int pixelFormat) {
        public Info {
            if (width < 1 || height < 1 || maxWidth < width || maxHeight < height || maxWidth > 4096 || maxHeight > 4096
                    || (long) maxWidth * maxHeight * 4 > 32 * 1024 * 1024 || !Float.isFinite(aspect) || aspect < 0 || aspect > 100
                    || !Double.isFinite(fps) || fps < 1 || fps > 240 || !Double.isFinite(sampleRate)
                    || sampleRate < 8000 || sampleRate > 192000 || pixelFormat < 0 || pixelFormat > 2)
                throw new IllegalArgumentException("Invalid libretro AV metadata");
        }
    }
    /** New owned output arrays; these never alias native memory or another result. */
    public record Output(Info info, boolean duplicate, byte[] rgba, short[] stereo, byte[] memory) { }
    public record Controls(int[] pads, int gun) {
        public Controls { pads = pads.clone(); }
        @Override public int[] pads() { return pads.clone(); }
    }
    private final Thread owner = Thread.currentThread();
    private final LibretroProfile profile;
    private final Class<?> coreResourceOwner;
    private final ByteTail logs = new ByteTail();
    private final String token = UUID.randomUUID().toString();
    private Path directory;
    private cn.piq.retro.storage.RuntimeWorkspace workspace;
    private Process child;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread logReader;
    private boolean closed, loaded;
    private Info info;
    private String coreVersion;
    private byte[] persistenceIdentity;

    public LibretroProcess(LibretroProfile profile) { this(profile, LibretroProcess.class); }
    /** Core resources belong to the declaring addon module; helper/JNA remain owned by the shared bridge. */
    public LibretroProcess(LibretroProfile profile, Class<?> coreResourceOwner) {
        this.profile = Objects.requireNonNull(profile);
        this.coreResourceOwner = Objects.requireNonNull(coreResourceOwner);
    }
    public Info info() { requireLoaded(); return info; }
    public String coreVersion() { requireLoaded(); return coreVersion; }
    public static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) throw new IllegalStateException("libretro prototype requires x86-64");
        if (os.startsWith("windows")) return "windows-x64";
        if (os.startsWith("linux")) return "linux-x64";
        throw new IllegalStateException("libretro prototype supports Windows/Linux x86-64");
    }

    public Info load(byte[] content) {
        check();
        if (loaded || directory != null) throw new IllegalStateException("Core already started");
        if (content == null || content.length < 1 || content.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Content size");
        try {
            start();
            try (var limit = deadline(30000)) {
                out.writeInt(1); out.writeUTF(profile.name()); out.writeUTF(profile.extension()); out.writeBoolean(profile.fullPath());
                out.writeInt(profile.devices().size()); for (int device : profile.devices()) out.writeInt(device);
                out.writeInt(profile.mesenGun() ? 1 : 0); out.writeInt(profile.options().size());
                for (var item : profile.options().entrySet()) { out.writeUTF(item.getKey()); out.writeUTF(item.getValue()); }
                out.writeInt(content.length); out.write(content); out.flush(); response();
                String name = in.readUTF(); coreVersion = in.readUTF();
                if (!name.equals(profile.name()) || coreVersion.isBlank() || coreVersion.length() > 128) throw new IOException("Unexpected core identity");
                info = metadata(); persistenceIdentity = persistenceIdentity(content);
                loaded = true; socket.setSoTimeout(10000); return info;
            }
        } catch (IOException | RuntimeException error) { throw fail(error); }
    }

    private void start() throws IOException {
        String platform = platform();
        var core = profile.cores().get(platform);
        if (core == null) throw new IOException("No verified core for " + platform);
        Properties manifest = new Properties();
        try (InputStream input = resource("/core/libretro-generic/runtime.properties")) { manifest.load(input); }
        workspace = cn.piq.retro.storage.RuntimeWorkspace.create("libretro", 352L * 1024 * 1024);
        directory = workspace.directory();
        Path worker = extract("/core/libretro-generic/worker.jar", manifest.getProperty("worker.sha256"), "worker.jar");
        Path jna = extract("/core/libretro/jna-5.14.0.jar", manifest.getProperty("jna.sha256"), "jna.jar");
        Path library = extract(coreResourceOwner, core.resource(), core.sha256(), platform.startsWith("windows") ? "core.dll" : "core.so");
        Path work = Files.createDirectory(directory.resolve("session"));
        InetAddress address = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (ServerSocket listener = new ServerSocket(0, 1, address)) {
            listener.setSoTimeout(500);
            Path java = Path.of(System.getProperty("java.home"), "bin", platform.startsWith("windows") ? "java.exe" : "java");
            var builder = new ProcessBuilder(java.toString(), "-Xmx192m", "-Djna.nosys=true", "-Djava.net.preferIPv4Stack=true",
                    "-Djava.io.tmpdir=" + directory, "-Djna.tmpdir=" + directory,
                    "-Djava.net.preferIPv6Addresses=false", "-cp", worker + File.pathSeparator + jna,
                    "cn.piq.retro.worker.GenericLibretroWorker", Integer.toString(listener.getLocalPort()), token, library.toString(), work.toString());
            builder.directory(directory.toFile()).redirectErrorStream(true);
            for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            builder.environment().put("TEMP", directory.toString()); builder.environment().put("TMP", directory.toString());
            child = workspace.start(builder); CHILDREN.add(child);
            Process owned = child;
            logReader = new Thread(() -> {
                try (InputStream input = owned.getInputStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while ((count = input.read(buffer)) >= 0) logs.add(buffer, count);
                } catch (IOException ignored) { }
            }, "libretro-generic-output");
            logReader.setDaemon(true); logReader.start();
            long ends = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (socket == null && System.nanoTime() < ends) {
                if (!child.isAlive()) throw new IOException("Generic libretro worker exited during startup (" + child.exitValue() + ")");
                try {
                    Socket candidate = listener.accept();
                    long remaining = TimeUnit.NANOSECONDS.toMillis(ends - System.nanoTime());
                    if (remaining <= 0) { candidate.close(); break; }
                    candidate.setSoTimeout((int) Math.max(1, Math.min(2000, remaining)));
                    try {
                        var hello = new DataInputStream(candidate.getInputStream());
                        if (hello.readInt() != MAGIC || hello.readInt() != 3 || !token.equals(hello.readUTF())) throw new IOException("Worker handshake");
                        socket = candidate;
                    } catch (IOException error) { candidate.close(); }
                } catch (SocketTimeoutException ignored) { }
            }
            if (socket == null) throw new IOException("Generic libretro worker startup timeout");
            socket.setTcpNoDelay(true); socket.setSoTimeout(30000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            try (var limit = deadline(10000)) { out.writeInt(0x4f4b4159); out.flush(); }
        }
    }

    public Output run(List<Controls> frames, int outputMask) {
        return runWithMemory(frames, outputMask, -1);
    }
    /** Pipeline a memory query with RUN, avoiding an additional parent/worker round-trip. */
    public Output runWithMemory(List<Controls> frames, int outputMask, int memoryId) {
        requireLoaded();
        if (frames == null || frames.isEmpty() || frames.size() > 120 || (outputMask & ~3) != 0) throw new IllegalArgumentException("Run batch");
        if (memoryId < -1 || memoryId > 3) throw new IllegalArgumentException("Memory id");
        frames = List.copyOf(frames);
        for (var frame : frames) {
            if (frame == null || frame.pads.length != profile.devices().size()) throw new IllegalArgumentException("Controller count");
            for (int pad : frame.pads) if ((pad & ~65535) != 0) throw new IllegalArgumentException("RetroPad mask");
            int gun = frame.gun;
            if ((!profile.mesenGun() && gun != 0) || (gun & ~0x3ffff) != 0
                    || profile.mesenGun() && (((gun >>> 8) & 255) >= 240 || ((gun & 65536) != 0 && (gun & 65535) != 0)))
                throw new IllegalArgumentException("Gun input");
        }
        try (var limit = deadline(10000)) {
            out.writeInt(2); out.writeInt(frames.size()); out.writeInt(outputMask);
            for (var frame : frames) { for (int pad : frame.pads) out.writeInt(pad); out.writeInt(frame.gun); }
            if (memoryId >= 0) { out.writeInt(7); out.writeInt(memoryId); }
            out.flush(); response(); info = metadata(); boolean duplicate = in.readBoolean();
            int bytes = in.readInt();
            if (bytes != ((outputMask & VIDEO) == 0 ? 0 : info.width() * info.height() * 4)) throw new IOException("Unexpected video length");
            byte[] rgba = new byte[bytes]; in.readFully(rgba);
            int count = in.readInt();
            if (count < 0 || count > 524288 || (count & 1) != 0 || ((outputMask & AUDIO) == 0 && count != 0)) throw new IOException("Unexpected PCM length");
            short[] pcm = new short[count]; for (int i = 0; i < count; i++) pcm[i] = in.readShort();
            byte[] memory = new byte[0];
            if (memoryId >= 0) { response(); memory = bytes(0, MAX_STATE); }
            return new Output(info, duplicate, rgba, pcm, memory);
        } catch (IOException | RuntimeException error) { throw fail(error); }
    }
    public byte[] serialize() {
        requireLoaded();
        try (var limit = deadline(10000)) { out.writeInt(3); out.flush(); response(); return bytes(1, MAX_STATE); }
        catch (IOException | RuntimeException error) { throw fail(error); }
    }
    public void restore(byte[] state) {
        requireLoaded();
        if (state == null || state.length < 1 || state.length > MAX_STATE) throw new IllegalArgumentException("State length");
        try (var limit = deadline(10000)) { out.writeInt(4); out.writeInt(state.length); out.write(state); out.flush(); response(); }
        catch (IOException | RuntimeException error) { throw fail(error); }
    }
    public Info reset() {
        requireLoaded();
        try (var limit = deadline(10000)) { out.writeInt(5); out.flush(); response(); return info = metadata(); }
        catch (IOException | RuntimeException error) { throw fail(error); }
    }
    public byte[] memory(int id) {
        requireLoaded(); if (id < 0 || id > 3) throw new IllegalArgumentException("Memory id");
        try (var limit = deadline(10000)) { out.writeInt(7); out.writeInt(id); out.flush(); response(); return bytes(0, MAX_STATE); }
        catch (IOException | RuntimeException error) { throw fail(error); }
    }
    /** Conservative per-content/core/options identity, NOT a player or cartridge owner ID. */
    public byte[] persistenceIdentity() { requireLoaded(); return persistenceIdentity.clone(); }
    private byte[] persistenceIdentity(byte[] content) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new ByteArrayOutputStream();
            try (var identity = new DataOutputStream(buffer)) {
                identity.writeUTF("libretro-save-memory-v1");
                identity.write(digest.digest(content));
                identity.writeUTF(profile.name()); identity.writeUTF(coreVersion);
                identity.writeUTF(profile.cores().get(platform()).sha256().toLowerCase(Locale.ROOT));
                identity.writeUTF(profile.extension()); identity.writeBoolean(profile.fullPath());
                identity.writeBoolean(profile.mesenGun()); identity.writeInt(profile.devices().size());
                for (int device : profile.devices()) identity.writeInt(device);
                identity.writeInt(profile.options().size());
                for (var entry : new TreeMap<>(profile.options()).entrySet()) {
                    identity.writeUTF(entry.getKey()); identity.writeUTF(entry.getValue());
                }
            }
            return digest.digest(buffer.toByteArray());
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    /** Capture on the owner thread, with no simulated frame between the two regions. */
    public LibretroSaveMemory saveMemory() { return new LibretroSaveMemory(memory(0), memory(1)); }
    public void restoreSaveMemory(LibretroSaveMemory data) {
        requireLoaded(); Objects.requireNonNull(data);
        byte[] ram = data.ram(), rtc = data.rtc();
        try (var limit = deadline(10000)) {
            out.writeInt(8); out.writeInt(ram.length); out.write(ram);
            out.writeInt(rtc.length); out.write(rtc); out.flush(); response();
        } catch (IOException | RuntimeException error) { throw fail(error); }
    }
    private Info metadata() throws IOException {
        return new Info(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readFloat(), in.readDouble(), in.readDouble(), in.readInt());
    }
    private byte[] bytes(int min, int max) throws IOException {
        int length = in.readInt(); if (length < min || length > max) throw new IOException("Invalid payload length");
        byte[] value = new byte[length]; in.readFully(value); return value;
    }
    private void response() throws IOException {
        if (!in.readBoolean()) { String message = in.readUTF(); throw new IOException("Libretro core: " + message.substring(0, Math.min(message.length(), 512))); }
    }
    private static InputStream resource(String name) throws IOException {
        InputStream value = LibretroProcess.class.getResourceAsStream(name);
        if (value == null) throw new IOException("Missing bundled libretro resource: " + name); return value;
    }
    private Path extract(String resource, String expected, String filename) throws IOException {
        return extract(LibretroProcess.class, resource, expected, filename);
    }
    private Path extract(Class<?> resourceOwner, String resource, String expected, String filename) throws IOException {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}")) throw new IOException("Runtime checksum missing");
        Path destination = directory.resolve(filename);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
        InputStream bundled = resourceOwner.getResourceAsStream(resource);
        if (bundled == null) throw new IOException("Missing bundled libretro resource: " + resource);
        try (InputStream input = bundled; OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536]; long total = 0; int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count; if (total > 256L * 1024 * 1024) throw new IOException("Runtime artifact exceeds limit");
                digest.update(buffer, 0, count); output.write(buffer, 0, count);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) throw new IOException("Runtime checksum mismatch: " + resource);
        return destination;
    }
    private Deadline deadline(long milliseconds) { return new Deadline(child, socket, milliseconds); }
    private static final class Deadline implements AutoCloseable {
        final AtomicInteger state = new AtomicInteger(); final ScheduledFuture<?> timer;
        Deadline(Process owned, Socket connection, long ms) {
            timer = TIMERS.schedule(() -> {
                if (!state.compareAndSet(0, 2)) return;
                try { connection.close(); } catch (IOException ignored) { }
                if (owned != null && owned.isAlive()) owned.destroyForcibly();
            }, ms, TimeUnit.MILLISECONDS);
        }
        @Override public void close() throws SocketTimeoutException {
            boolean done = state.compareAndSet(0, 1); timer.cancel(false);
            if (!done && state.get() == 2) throw new SocketTimeoutException("Libretro operation timed out");
        }
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Core accessed outside owning thread");
        if (closed) throw new IllegalStateException("Core closed");
    }
    private void requireLoaded() { check(); if (!loaded) throw new IllegalStateException("No content loaded"); }
    private IllegalStateException fail(Exception error) {
        dispose(); closed = true;
        String detail = logs.text().replace(token, "[redacted]");
        String reason = workspace == null ? error.getMessage() : workspace.failureMessage(error.getMessage());
        return new IllegalStateException("Generic libretro failed: " + reason + (detail.isEmpty() ? "" : "\n" + detail), error);
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Core closed outside owning thread");
        if (!closed) { dispose(); closed = true; }
    }
    private void dispose() {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        if (child != null) {
            child.destroy();
            try { if (!child.waitFor(500, TimeUnit.MILLISECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); } }
            catch (InterruptedException error) { child.destroyForcibly(); Thread.currentThread().interrupt(); }
            if (!child.isAlive()) CHILDREN.remove(child);
        }
        if (logReader != null) try { logReader.join(250); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (child != null && child.isAlive()) child.onExit().thenRun(() -> CHILDREN.remove(child));
        if (workspace != null) workspace.close();
    }
    private static final class ByteTail {
        final byte[] bytes = new byte[8192]; int size, position;
        synchronized void add(byte[] value, int count) {
            for (int i = Math.max(0, count - bytes.length); i < count; i++) {
                bytes[position] = value[i]; position = (position + 1) % bytes.length; size = Math.min(size + 1, bytes.length);
            }
        }
        synchronized String text() {
            byte[] result = new byte[size];
            for (int i = 0; i < size; i++) result[i] = bytes[(position - size + i + bytes.length) % bytes.length];
            return new String(result, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        }
    }
}
