package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One isolated, owned native core process per session. Not an OS security sandbox. */
public final class LibretroNesCore implements NesCore {
    public static final String MODULE_RESOURCE = "/core/libretro/mesen-profile.properties";
    public static final String PROFILE_SHA256 = profileHash();
    private static final int MAGIC = 0x504C5231, MAX_STATE = 16 * 1024 * 1024;
    private static final int STATE_CACHE_BYTES = 4 * Integer.BYTES + RGBA_BYTES + CPU_RAM_BYTES;
    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static ScheduledThreadPoolExecutor deadlines() {
        var scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fc-libretro-deadline"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    /** Hard deadlines also break blocked writes. Cancellation cannot kill a subsequent operation. */
    static final class Deadline implements AutoCloseable {
        private final AtomicInteger state = new AtomicInteger(); // 0 active, 1 completed, 2 expired
        private final ScheduledFuture<?> timer;
        Deadline(Process child, Socket connection, long milliseconds) {
            if (milliseconds < 1) throw new IllegalArgumentException("Operation timeout");
            timer = DEADLINES.schedule(() -> {
                if (!state.compareAndSet(0, 2)) return;
                try { connection.close(); } catch (IOException ignored) { }
                if (child != null && child.isAlive()) child.destroyForcibly();
            }, milliseconds, TimeUnit.MILLISECONDS);
        }
        @Override public void close() throws SocketTimeoutException {
            boolean completed = state.compareAndSet(0, 1);
            timer.cancel(false);
            if (!completed && state.get() == 2) throw new SocketTimeoutException("FC core operation timed out");
        }
    }
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process process : CHILDREN) process.destroyForcibly();
        }, "fc-libretro-shutdown"));
    }
    private final Thread owner = Thread.currentThread();
    private final boolean zapper;
    private final byte[] frame = new byte[RGBA_BYTES], ram = new byte[CPU_RAM_BYTES];
    private final float[] audio = new float[4096];
    private int samples, p1, p2, gun = 1 << 16;
    private byte[] romHash;
    private Path directory;
    private cn.piq.retro.storage.RuntimeWorkspace workspace;
    private Process process;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final WorkerOutput workerOutput = new WorkerOutput();
    private Thread logDrainer;
    private String workerToken;
    private boolean closed;

    /** Retain only a bounded tail; native output never enters the binary protocol. */
    static final class WorkerOutput {
        private final byte[] tail = new byte[8192];
        private int position, size;
        synchronized void append(byte[] bytes, int count) {
            for (int i = Math.max(0, count - tail.length); i < count; i++) {
                tail[position] = bytes[i];
                position = (position + 1) % tail.length;
                size = Math.min(size + 1, tail.length);
            }
        }
        synchronized String text(String token) {
            byte[] ordered = new byte[size];
            for (int i = 0; i < size; i++) ordered[i] = tail[(position - size + i + tail.length) % tail.length];
            String value = new String(ordered, StandardCharsets.UTF_8);
            if (token != null && !token.isEmpty()) value = value.replace(token, "[redacted]");
            return value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        }
    }

    public LibretroNesCore(boolean zapper) { this.zapper = zapper; }

    public static String unavailableReason() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(arch.equals("amd64") || arch.equals("x86_64"))) return "FC libretro requires x86-64";
        return os.startsWith("windows") || os.startsWith("linux") ? null : "FC libretro supports Windows and Linux x86-64";
    }

    @Override public void loadRom(byte[] rom) {
        check();
        if (romHash != null) throw new IllegalStateException("ROM already loaded");
        if (rom == null || rom.length < 16 || rom.length > 32 * 1024 * 1024) throw new IllegalArgumentException("ROM size");
        String reason = unavailableReason();
        if (reason != null) throw new IllegalStateException(reason);
        try {
            start();
            try (Deadline deadline = new Deadline(process, socket, 30_000)) {
                out.writeInt(1); out.writeBoolean(zapper); out.writeInt(rom.length); out.write(rom); out.flush();
                response();
                String name = in.readUTF(), version = in.readUTF();
                double fps = in.readDouble(), rate = in.readDouble();
                if (!name.equals("Mesen") || version.isBlank() || fps < 59 || fps > 61 || rate != 44100) {
                    throw new IOException("Unexpected core profile: " + name + " " + fps + "/" + rate);
                }
                romHash = hash(rom);
                socket.setSoTimeout(10_000);
            }
        } catch (IOException | RuntimeException e) { throw failed(e); }
    }

    private void start() throws IOException {
        Properties manifest = new Properties();
        try (InputStream stream = resource("/core/libretro/runtime.properties")) { manifest.load(stream); }
        Properties profile = new Properties();
        byte[] profileData = profileBytes();
        if (!HexFormat.of().formatHex(hash(profileData)).equals(PROFILE_SHA256)) throw new IOException("FC profile changed after initialization");
        try (InputStream stream = new ByteArrayInputStream(profileData)) { profile.load(stream); }
        String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "windows-x64" : "linux-x64";
        for (String key : List.of(platform + ".sha256", "worker.sha256", "jna.sha256")) {
            String pinned = profile.getProperty(key);
            if (pinned == null || !pinned.matches("[a-fA-F0-9]{64}") || !pinned.equalsIgnoreCase(manifest.getProperty(key, "")))
                throw new IOException("Bundled runtime differs from FC state profile: " + key);
        }
        String pinnedCore = profile.getProperty(platform + ".sha256");
        workspace = cn.piq.retro.storage.RuntimeWorkspace.create("fc-legacy", 128L * 1024 * 1024);
        directory = workspace.directory();
        Path helper = extract("worker.jar", manifest.getProperty("worker.sha256"));
        Path jna = extract("jna-5.14.0.jar", manifest.getProperty("jna.sha256"));
        String coreName = platform.equals("windows-x64") ? "mesen_libretro.dll" : "mesen_libretro.so";
        Path core = extract(platform + "/" + coreName, pinnedCore);
        Path sessionDirectory = Files.createDirectory(directory.resolve("session"));
        String token = workerToken = UUID.randomUUID().toString();
        // Launchers can set preferIPv6Addresses=system/true only in Minecraft.
        // Use an explicit private IPv4 endpoint in BOTH JVMs, never a wildcard.
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            server.setSoTimeout(1000);
            Path java = Path.of(System.getProperty("java.home"), "bin", platform.equals("windows-x64") ? "java.exe" : "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx128m", "-Djna.nosys=true",
                    "-Djava.io.tmpdir=" + directory, "-Djna.tmpdir=" + directory,
                    "-Djava.net.preferIPv4Stack=true", "-Djava.net.preferIPv6Addresses=false", "-cp",
                    helper + File.pathSeparator + jna, "cn.piq.fcarcade.libretro.worker.MesenWorker",
                    Integer.toString(server.getLocalPort()), token, core.toString(), sessionDirectory.toString());
            builder.directory(directory.toFile()).redirectErrorStream(true);
            for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            builder.environment().put("TEMP", directory.toString()); builder.environment().put("TMP", directory.toString());
            process = workspace.start(builder); CHILDREN.add(process);
            Process child = process;
            logDrainer = new Thread(() -> {
                try (InputStream log = child.getInputStream()) {
                    byte[] bytes = new byte[2048]; int count;
                    while ((count = log.read(bytes)) >= 0) workerOutput.append(bytes, count);
                }
                catch (IOException ignored) { }
            }, "fc-libretro-log");
            logDrainer.setDaemon(true); logDrainer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (socket == null && System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("FC native worker exited during startup (" + process.exitValue() + ")");
                try {
                    Socket candidate = server.accept();
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) { candidate.close(); break; }
                    candidate.setSoTimeout((int)Math.min(2000, Math.max(1, remaining)));
                    DataInputStream hello = new DataInputStream(candidate.getInputStream());
                    try {
                        if (hello.readInt() != MAGIC || hello.readInt() != 1 || !token.equals(hello.readUTF())) throw new IOException("Worker handshake");
                        socket = candidate; in = hello;
                    } catch (IOException rejected) { candidate.close(); }
                } catch (SocketTimeoutException retry) { /* bounded child/deadline check */ }
            }
            if (socket == null) throw new IOException("FC native worker startup timeout");
            socket.setTcpNoDelay(true); socket.setSoTimeout(30_000);
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            try (Deadline operation = new Deadline(process, socket, 10_000)) {
                out.writeInt(0x4F4B4159); out.flush();
            }
        }
    }

    private Path extract(String name, String expected) throws IOException {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}")) throw new IOException("Missing runtime checksum");
        byte[] data;
        try (InputStream source = resource("/core/libretro/" + name)) { data = source.readNBytes(32 * 1024 * 1024 + 1); }
        if (data.length > 32 * 1024 * 1024 || !HexFormat.of().formatHex(hash(data)).equalsIgnoreCase(expected)) throw new IOException("Runtime checksum mismatch: " + name);
        Path file = directory.resolve(Path.of(name).getFileName());
        Files.write(file, data, StandardOpenOption.CREATE_NEW);
        return file;
    }
    private static InputStream resource(String name) throws IOException {
        InputStream stream = LibretroNesCore.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Missing bundled FC runtime: " + name);
        return stream;
    }
    private static String profileHash() {
        try { return HexFormat.of().formatHex(hash(profileBytes())); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static byte[] profileBytes() throws IOException {
        try (InputStream stream = resource(MODULE_RESOURCE)) {
            byte[] bytes = stream.readNBytes(65537);
            if (bytes.length == 0 || bytes.length > 65536) throw new IOException("FC profile size");
            return bytes;
        }
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("NES core accessed outside owning thread");
        if (closed) throw new IllegalStateException("NES core closed");
    }
    private void loaded() { check(); if (romHash == null) throw new IllegalStateException("No ROM loaded"); }
    private void response() throws IOException { if (!in.readBoolean()) throw new IOException("FC core: " + in.readUTF()); }
    private IllegalStateException failed(Exception error) {
        dispose(); closed = true;
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String detail = workerOutput.text(workerToken);
        return new IllegalStateException("FC libretro failed: " + message
                + (detail.isEmpty() ? "" : "\nFC worker output:\n" + detail), error);
    }

    @Override public void setControllerState(int player, int mask) {
        check(); if (player < 0 || player > 1 || (mask & ~255) != 0) throw new IllegalArgumentException("Controller input");
        if (player == 0) p1 = mask; else p2 = mask;
    }
    @Override public boolean supportsZapper() { return zapper; }
    @Override public void setZapperState(int x, int y, boolean offscreen, boolean trigger) {
        check(); if (!zapper) throw new UnsupportedOperationException("No light gun");
        if (!offscreen && (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT)) throw new IllegalArgumentException("Aim outside screen");
        gun = (offscreen ? 1 << 16 : x | y << 8) | (trigger ? 1 << 17 : 0);
    }
    @Override public String stateNamespace() { return (zapper ? "nes-libretro-mesen-zapper-v1/" : "nes-libretro-mesen-v1/") + PROFILE_SHA256; }
    @Override public void runFrame() {
        loaded();
        try (Deadline deadline = new Deadline(process, socket, 10_000)) {
            out.writeInt(2); out.writeInt(p1); out.writeInt(p2); out.writeInt(gun); out.flush(); response();
            if (in.readInt() != frame.length) throw new IOException("Invalid frame dimensions"); in.readFully(frame);
            samples = in.readInt(); if (samples < 0 || samples > audio.length) throw new IOException("Audio size");
            for (int n = 0; n < samples; n++) { audio[n] = in.readFloat(); if (!Float.isFinite(audio[n])) throw new IOException("Invalid audio"); }
            if (in.readInt() != ram.length) throw new IOException("CPU RAM size"); in.readFully(ram);
        } catch (IOException e) { throw failed(e); }
    }
    @Override public void copyFrameRgba(byte[] destination) { loaded(); if (destination.length < frame.length) throw new IllegalArgumentException("Frame capacity"); System.arraycopy(frame, 0, destination, 0, frame.length); }
    @Override public int copyAudioSamples(float[] destination) { loaded(); if (destination.length < samples) throw new IllegalArgumentException("Audio capacity"); System.arraycopy(audio, 0, destination, 0, samples); return samples; }
    @Override public void copyCpuRam(byte[] destination) { loaded(); if (destination.length < ram.length) throw new IllegalArgumentException("RAM capacity"); System.arraycopy(ram, 0, destination, 0, ram.length); }
    @Override public void reset() {
        loaded(); try (Deadline deadline = new Deadline(process, socket, 10_000)) { out.writeInt(5); out.flush(); response(); p1 = p2 = samples = 0; gun = 1 << 16; Arrays.fill(frame, (byte)0); Arrays.fill(ram, (byte)0); }
        catch (IOException e) { throw failed(e); }
    }
    @Override public byte[] saveTransientState() {
        loaded(); try (Deadline deadline = new Deadline(process, socket, 10_000)) {
            out.writeInt(3); out.flush(); response(); int size = in.readInt();
            if (size < 1 || size > MAX_STATE - STATE_CACHE_BYTES) throw new IOException("Snapshot size");
            byte[] raw = new byte[size]; in.readFully(raw);
            int payloadSize = STATE_CACHE_BYTES + size;
            return ByteBuffer.allocate(80 + payloadSize).putInt(MAGIC).putInt(1).putInt(zapper ? 1 : 0)
                    .put(HexFormat.of().parseHex(PROFILE_SHA256)).put(romHash).putInt(payloadSize)
                    .putInt(size).put(raw).put(frame).put(ram).putInt(p1).putInt(p2).putInt(gun).array();
        } catch (IOException e) { throw failed(e); }
    }
    @Override public void loadTransientState(byte[] state) {
        loaded();
        if (state == null || state.length < 81 + STATE_CACHE_BYTES || state.length > MAX_STATE + 80) throw new IllegalArgumentException("Snapshot size");
        ByteBuffer buffer = ByteBuffer.wrap(state);
        if (buffer.getInt() != MAGIC || buffer.getInt() != 1 || buffer.getInt() != (zapper ? 1 : 0)) throw new IllegalArgumentException("Different snapshot core or mode");
        byte[] identity = new byte[32], rom = new byte[32]; buffer.get(identity); buffer.get(rom); int length = buffer.getInt();
        if (!MessageDigest.isEqual(identity, HexFormat.of().parseHex(PROFILE_SHA256)) || !MessageDigest.isEqual(rom, romHash)
                || length != buffer.remaining() || length <= STATE_CACHE_BYTES) throw new IllegalArgumentException("Different snapshot profile or game");
        int nativeLength = buffer.getInt();
        if (nativeLength < 1 || nativeLength > MAX_STATE - STATE_CACHE_BYTES || nativeLength != length - STATE_CACHE_BYTES)
            throw new IllegalArgumentException("Snapshot native state size");
        int controlsAt = state.length - 3 * Integer.BYTES;
        int savedP1 = buffer.getInt(controlsAt), savedP2 = buffer.getInt(controlsAt + 4), savedGun = buffer.getInt(controlsAt + 8);
        boolean offscreen = (savedGun & (1 << 16)) != 0;
        if ((savedP1 & ~255) != 0 || (savedP2 & ~255) != 0 || (savedGun & ~0x3ffff) != 0
                || offscreen && (savedGun & 0xffff) != 0 || !offscreen && ((savedGun >>> 8) & 255) >= HEIGHT
                || !zapper && savedGun != (1 << 16)) throw new IllegalArgumentException("Snapshot controller state");
        try (Deadline deadline = new Deadline(process, socket, 10_000)) {
            out.writeInt(4); out.writeInt(nativeLength); out.write(state, 84, nativeLength); out.flush(); response();
            buffer.position(84 + nativeLength); buffer.get(frame); buffer.get(ram); samples = 0;
            p1 = savedP1; p2 = savedP2; gun = savedGun;
        }
        catch (IOException e) { throw failed(e); }
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("NES core closed outside owning thread");
        if (closed) return; dispose(); closed = true;
    }
    private void dispose() {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        if (process != null) {
            process.destroy();
            try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            if (!process.isAlive()) CHILDREN.remove(process);
        }
        if (logDrainer != null) {
            try { logDrainer.join(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (process != null && process.isAlive()) process.onExit().thenRun(() -> CHILDREN.remove(process));
        if (workspace != null) workspace.close();
    }
}
