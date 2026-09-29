package cn.piq.retro.worker;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static cn.piq.retro.worker.WorkerProtocol.*;

/** Isolated software libretro runner. No Minecraft state, network emulation, or download API. */
public final class GenericLibretroWorker {
    public interface Retro extends Library {
        int retro_api_version();
        void retro_set_environment(Environment callback);
        void retro_set_video_refresh(Video callback);
        void retro_set_audio_sample(Audio callback);
        void retro_set_audio_sample_batch(AudioBatch callback);
        void retro_set_input_poll(InputPoll callback);
        void retro_set_input_state(InputState callback);
        void retro_init();
        void retro_deinit();
        void retro_get_system_info(SystemInfo info);
        void retro_get_system_av_info(AvInfo info);
        byte retro_load_game(GameInfo game);
        void retro_unload_game();
        void retro_set_controller_port_device(int port, int device);
        void retro_run();
        void retro_reset();
        long retro_serialize_size();
        byte retro_serialize(Pointer destination, long size);
        byte retro_unserialize(Pointer source, long size);
        Pointer retro_get_memory_data(int id);
        long retro_get_memory_size(int id);
    }
    public interface Environment extends Callback { byte invoke(int command, Pointer data); }
    public interface Video extends Callback { void invoke(Pointer data, int width, int height, long pitch); }
    public interface Audio extends Callback { void invoke(short left, short right); }
    public interface AudioBatch extends Callback { long invoke(Pointer data, long frames); }
    public interface InputPoll extends Callback { void invoke(); }
    public interface InputState extends Callback { short invoke(int port, int device, int index, int id); }
    @Structure.FieldOrder({"path", "data", "size", "meta"})
    public static final class GameInfo extends Structure {
        public Pointer path, data;
        public long size;
        public Pointer meta;
    }
    @Structure.FieldOrder({"fullPath", "archivePath", "archiveFile", "directory", "name", "extension", "meta", "data", "size", "inArchive", "persistent"})
    public static final class ExtendedGameInfo extends Structure {
        public Pointer fullPath, archivePath, archiveFile, directory, name, extension, meta, data;
        public long size;
        public byte inArchive, persistent;
    }
    @Structure.FieldOrder({"libraryName", "libraryVersion", "validExtensions", "needFullpath", "blockExtract"})
    public static final class SystemInfo extends Structure {
        public Pointer libraryName, libraryVersion, validExtensions;
        public byte needFullpath, blockExtract;
    }
    @Structure.FieldOrder({"baseWidth", "baseHeight", "maxWidth", "maxHeight", "aspectRatio"})
    public static final class Geometry extends Structure {
        public int baseWidth, baseHeight, maxWidth, maxHeight;
        public float aspectRatio;
    }
    @Structure.FieldOrder({"fps", "sampleRate"})
    public static final class Timing extends Structure { public double fps, sampleRate; }
    @Structure.FieldOrder({"geometry", "timing"})
    public static final class AvInfo extends Structure {
        public Geometry geometry = new Geometry();
        public Timing timing = new Timing();
    }

    private GenericLibretroWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || Native.POINTER_SIZE != 8) throw new IOException("Expected private 64-bit worker arguments");
        int port = Integer.parseInt(args[0]);
        String token = args[1];
        if (port < 1 || port > 65535 || !token.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("Invalid endpoint");
        Path corePath = Path.of(args[2]).toAbsolutePath().normalize();
        Path work = Path.of(args[3]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(corePath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid isolated core/work directory");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 10000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(token); out.flush();
                if (in.readInt() != ACK) throw new IOException("Worker authentication rejected");
                socket.setSoTimeout(0); // Parent enforces operation deadlines, including blocked native calls.
                Retro core = Native.load(corePath.toString(), Retro.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                if (core.retro_api_version() != 1) throw new IOException("Unsupported libretro ABI");
                try (Engine engine = new Engine(core, work)) { commands(engine, in, out); }
            }
        }
    }

    static void commands(Engine e, DataInputStream in, DataOutputStream out) throws IOException {
        for (;;) {
            int command;
            try { command = in.readInt(); } catch (EOFException disconnected) { return; }
            try {
                switch (command) {
                    case LOAD -> {
                        String expected = text(in, 128, "core name"), extension = text(in, 16, "extension");
                        boolean fullpath = in.readBoolean();
                        int count = bounded(in.readInt(), 1, MAX_PORTS, "port count");
                        int[] devices = new int[count];
                        for (int i = 0; i < count; i++) devices[i] = in.readInt();
                        int gunMode = bounded(in.readInt(), 0, 1, "gun mode");
                        int optionCount = bounded(in.readInt(), 0, MAX_OPTIONS, "option count");
                        Map<String, String> options = new HashMap<>();
                        for (int i = 0; i < optionCount; i++) {
                            String key = text(in, 128, "option key"), value = text(in, 512, "option value");
                            if (options.put(key, value) != null) throw new IOException("Duplicate option key");
                        }
                        byte[] rom = bytes(in, 1, MAX_ROM, "ROM");
                        e.load(expected, extension, fullpath, devices, gunMode, options, rom);
                        out.writeBoolean(true); out.writeUTF(e.name); out.writeUTF(e.version); e.metadata(out);
                    }
                    case RUN -> {
                        e.requireLoaded();
                        int frames = bounded(in.readInt(), 1, MAX_RUN_FRAMES, "frame count");
                        int mask = bounded(in.readInt(), 0, VIDEO | AUDIO, "output mask");
                        int[][] pads = new int[frames][e.devices.length];
                        int[] guns = new int[frames];
                        for (int i = 0; i < frames; i++) {
                            for (int p = 0; p < pads[i].length; p++) pads[i][p] = bounded(in.readInt(), 0, 65535, "RetroPad input");
                            guns[i] = in.readInt(); e.validateGun(guns[i]);
                        }
                        e.run(pads, guns, mask);
                        out.writeBoolean(true); e.metadata(out); out.writeBoolean(e.duplicate);
                        out.writeInt((mask & VIDEO) == 0 ? 0 : e.frame.length);
                        if ((mask & VIDEO) != 0) out.write(e.frame);
                        out.writeInt(e.audioCount);
                        for (int i = 0; i < e.audioCount; i++) out.writeShort(e.samples[i]);
                    }
                    case SAVE -> { byte[] state = e.save(); out.writeBoolean(true); out.writeInt(state.length); out.write(state); }
                    case RESTORE -> { e.restore(bytes(in, 1, MAX_STATE, "state")); out.writeBoolean(true); }
                    case RESET -> { e.reset(); out.writeBoolean(true); e.metadata(out); }
                    case MEMORY -> {
                        byte[] memory = e.memory(bounded(in.readInt(), 0, 3, "memory id"));
                        out.writeBoolean(true); out.writeInt(memory.length); out.write(memory);
                    }
                    case RESTORE_SAVE_MEMORY -> {
                        byte[] ram = bytes(in, 0, MAX_MEMORY, "save RAM");
                        byte[] rtc = bytes(in, 0, MAX_MEMORY - ram.length, "RTC");
                        e.restoreSaveMemory(ram, rtc); out.writeBoolean(true);
                    }
                    case CLOSE -> { e.close(); out.writeBoolean(true); out.flush(); return; }
                    default -> throw new IOException("Unknown libretro worker command");
                }
                out.flush();
            } catch (Exception error) {
                // Result is computed before success is written. A transport write failure is terminal too.
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                out.writeBoolean(false); out.writeUTF(message.substring(0, Math.min(512, message.length()))); out.flush();
                return;
            }
        }
    }

    static int bounded(int value, int low, int high, String name) throws IOException {
        if (value < low || value > high) throw new IOException("Invalid " + name);
        return value;
    }
    static String text(DataInputStream in, int max, String kind) throws IOException {
        String value = in.readUTF();
        if (value.isEmpty() || value.length() > max || value.indexOf('\0') >= 0) throw new IOException("Invalid " + kind);
        return value;
    }
    static byte[] bytes(DataInputStream in, int min, int max, String name) throws IOException {
        int size = bounded(in.readInt(), min, max, name + " length");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
    static String nativeText(Pointer pointer, int max, String kind) throws IOException {
        if (pointer == null) throw new IOException("Missing " + kind);
        byte[] bytes = new byte[max];
        for (int i = 0; i < max; i++) {
            byte b = pointer.getByte(i);
            if (b == 0) return new String(bytes, 0, i, StandardCharsets.UTF_8);
            bytes[i] = b;
        }
        throw new IOException("Oversized " + kind);
    }

    static final class Engine implements AutoCloseable {
        final Retro core;
        final Path work;
        final Map<String, Memory> strings = new HashMap<>();
        final Map<String, String> defaults = new HashMap<>();
        final Map<String, String[]> offeredOptions = new HashMap<>();
        Map<String, String> options = Map.of();
        final Environment environment = this::environment;
        final Video video = this::video;
        final Audio audio = (left, right) -> {
            try { sample(left, right); } catch (Exception error) { callbackFailure = error; }
        };
        final AudioBatch batch = this::audioBatch;
        final InputPoll poll = () -> {};
        final InputState input = this::input;
        volatile Exception callbackFailure;
        boolean initialized, loaded, closed, duplicate = true, collectAudio;
        int width, height, maxWidth, maxHeight, pixelFormat, audioCount, gunMode, gun;
        int[] devices = new int[0], pads = new int[0], row32 = new int[0];
        short[] row16 = new short[0], samples = new short[MAX_AUDIO_FRAMES * 2], pcm = new short[0];
        byte[] frame = new byte[0];
        float aspect;
        String name, version;
        double fps, sampleRate;
        Memory romMemory;
        ExtendedGameInfo extendedGame;

        Engine(Retro core, Path work) { this.core = core; this.work = work; core.retro_set_environment(environment); }

        Memory string(String value) throws IOException {
            Memory cached = strings.get(value);
            if (cached != null) return cached;
            if (strings.size() >= 2048 || value.length() > 8192) throw new IOException("Too many native strings");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            Memory memory = new Memory(bytes.length + 1L);
            memory.write(0, bytes, 0, bytes.length); memory.setByte(bytes.length, (byte) 0);
            strings.put(value, memory); return memory;
        }

        void load(String expected, String extension, boolean fullpath, int[] configuredDevices,
                  int configuredGun, Map<String, String> pinnedOptions, byte[] rom) throws IOException {
            if (initialized || loaded || closed) throw new IOException("ROM already loaded");
            checkCallback();
            if (!extension.matches("[a-z0-9]{1,16}")) throw new IOException("Invalid ROM extension");
            SystemInfo info = new SystemInfo(); core.retro_get_system_info(info); info.read();
            name = nativeText(info.libraryName, 129, "core name"); version = nativeText(info.libraryVersion, 129, "core version");
            if (!expected.equals(name)) throw new IOException("Unexpected emulator core");
            String extensions = nativeText(info.validExtensions, 2048, "core extensions");
            if (Arrays.stream(extensions.split("\\|")).noneMatch(extension::equals)) throw new IOException("Core does not accept extension");
            if ((info.needFullpath != 0) != fullpath) throw new IOException("Content path mode differs from descriptor");
            if (configuredGun != 0 && !name.equals("Mesen")) throw new IOException("Mesen pointer bridge requires Mesen");
            for (int device : configuredDevices) {
                if (device < 0 || device > 65535 || (device != 0 && (device & 255) != 1 && !(configuredGun == 1 && device == 262))) {
                    throw new IOException("Unsupported input device; only RetroPad and explicit Mesen Zapper supported");
                }
            }
            if (configuredGun == 1 && (configuredDevices.length < 2 || configuredDevices[1] != 262)) throw new IOException("Mesen Zapper requires port 1");
            devices = configuredDevices.clone(); pads = new int[devices.length]; gunMode = configuredGun;
            gun = configuredGun == 0 ? 0 : 1 << 16;
            options = Map.copyOf(pinnedOptions);
            Path path = work.resolve("content." + extension);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Content file already exists");
            Files.write(path, rom, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            romMemory = new Memory(rom.length); romMemory.write(0, rom, 0, rom.length);
            extendedGame = new ExtendedGameInfo();
            extendedGame.fullPath = string(path.toString()); extendedGame.directory = string(work.toString());
            extendedGame.name = string("content"); extendedGame.extension = string(extension);
            extendedGame.data = romMemory; extendedGame.size = rom.length; extendedGame.persistent = 1; extendedGame.write();
            core.retro_init(); initialized = true; checkCallback();
            for (var option : options.entrySet()) {
                String[] offered = offeredOptions.get(option.getKey());
                if (offered == null || Arrays.stream(offered).noneMatch(option.getValue()::equals)) {
                    throw new IOException("Pinned core option is unsupported: " + option.getKey());
                }
            }
            // Some cores (including Mesen) need init before their input callback setters.
            core.retro_set_video_refresh(video); core.retro_set_audio_sample(audio); core.retro_set_audio_sample_batch(batch);
            core.retro_set_input_poll(poll); core.retro_set_input_state(input);
            GameInfo game = new GameInfo(); game.path = string(path.toString());
            if (!fullpath) { game.data = romMemory; game.size = rom.length; }
            game.write(); loaded = core.retro_load_game(game) != 0; checkCallback();
            if (!loaded) throw new IOException("Core rejected content");
            for (int port = 0; port < MAX_PORTS; port++) core.retro_set_controller_port_device(port, port < devices.length ? devices[port] : 0);
            // Mesen also exposes a fifth expansion slot; explicitly exclude DB-selected peripherals.
            if (name.equals("Mesen")) core.retro_set_controller_port_device(4, 0);
            updateAv(); checkCallback();
        }

        void validateGun(int value) throws IOException {
            if (gunMode == 0) { if (value != 0) throw new IOException("Gun input not enabled"); return; }
            if ((value & ~0x3ffff) != 0 || ((value >>> 8) & 255) >= 240
                    || ((value & (1 << 16)) != 0 && (value & 65535) != 0)) throw new IOException("Invalid gun input");
        }

        void run(int[][] inputs, int[] guns, int mask) throws IOException {
            requireLoaded(); audioCount = 0; collectAudio = (mask & AUDIO) != 0;
            for (int i = 0; i < inputs.length; i++) {
                System.arraycopy(inputs[i], 0, pads, 0, pads.length); gun = guns[i]; duplicate = true;
                core.retro_run(); checkCallback();
            }
        }

        void metadata(DataOutputStream out) throws IOException {
            out.writeInt(width); out.writeInt(height); out.writeInt(maxWidth); out.writeInt(maxHeight);
            out.writeFloat(aspect); out.writeDouble(fps); out.writeDouble(sampleRate); out.writeInt(pixelFormat);
        }
        void updateAv() throws IOException { AvInfo av = new AvInfo(); core.retro_get_system_av_info(av); av.read(); av(av); }
        void av(AvInfo av) throws IOException { geometry(av.geometry, true); timing(av.timing.fps, av.timing.sampleRate); }
        void geometry(Geometry geometry, boolean updateMaximum) throws IOException {
            validateGeometry(geometry.baseWidth, geometry.baseHeight);
            if (updateMaximum) {
                validateGeometry(geometry.maxWidth, geometry.maxHeight);
                if (geometry.maxWidth < geometry.baseWidth || geometry.maxHeight < geometry.baseHeight) throw new IOException("Invalid maximum geometry");
                maxWidth = geometry.maxWidth; maxHeight = geometry.maxHeight;
            } else if (maxWidth > 0 && (geometry.baseWidth > maxWidth || geometry.baseHeight > maxHeight)) throw new IOException("Geometry exceeds maximum");
            if (!Float.isFinite(geometry.aspectRatio) || geometry.aspectRatio < 0 || geometry.aspectRatio > 100) throw new IOException("Invalid aspect ratio");
            aspect = geometry.aspectRatio == 0 ? (float) geometry.baseWidth / geometry.baseHeight : geometry.aspectRatio;
            allocateFrame(geometry.baseWidth, geometry.baseHeight);
        }
        static void validateGeometry(int w, int h) throws IOException {
            if (w < 1 || h < 1 || w > MAX_DIMENSION || h > MAX_DIMENSION || (long) w * h * 4 > MAX_VIDEO_BYTES) throw new IOException("Video geometry exceeds limits");
        }
        void allocateFrame(int w, int h) {
            if (width == w && height == h && frame.length != 0) return;
            width = w; height = h; frame = new byte[w * h * 4];
            for (int i = 3; i < frame.length; i += 4) frame[i] = (byte) 255;
        }
        void timing(double f, double rate) throws IOException {
            if (!Double.isFinite(f) || f < 1 || f > 240 || !Double.isFinite(rate) || rate < 8000 || rate > 192000) throw new IOException("Core timing exceeds limits");
            fps = f; sampleRate = rate;
        }

        byte[] save() throws IOException {
            requireLoaded(); long size = core.retro_serialize_size();
            if (size < 1 || size > MAX_STATE) throw new IOException("Core state unavailable or exceeds limits");
            try (Memory memory = new Memory(size)) {
                if (core.retro_serialize(memory, size) == 0) throw new IOException("Core could not serialize");
                checkCallback(); return memory.getByteArray(0, (int) size);
            }
        }
        void restore(byte[] state) throws IOException {
            requireLoaded();
            if (state.length != core.retro_serialize_size()) throw new IOException("State/core size mismatch");
            try (Memory memory = new Memory(state.length)) {
                memory.write(0, state, 0, state.length);
                if (core.retro_unserialize(memory, state.length) == 0) throw new IOException("Core rejected state");
            }
            audioCount = 0; checkCallback(); updateAv();
        }
        void reset() throws IOException { requireLoaded(); core.retro_reset(); audioCount = 0; checkCallback(); updateAv(); }
        byte[] memory(int id) throws IOException {
            requireLoaded(); long size = core.retro_get_memory_size(id);
            if (size < 0 || size > MAX_MEMORY) throw new IOException("Core memory exceeds limits");
            if (size == 0) return new byte[0];
            Pointer pointer = core.retro_get_memory_data(id);
            if (pointer == null) throw new IOException("Core memory unavailable");
            return pointer.getByteArray(0, (int) size);
        }
        void restoreSaveMemory(byte[] ram, byte[] rtc) throws IOException {
            requireLoaded();
            // Only SAVE_RAM (0) and RTC (1), never CPU/system/video memory.
            // Validate BOTH regions before writing either. Core calls stay on this owner thread.
            byte[][] values = {ram, rtc};
            Pointer[] targets = new Pointer[2];
            for (int id = 0; id < 2; id++) {
                long size = core.retro_get_memory_size(id);
                if (size != values[id].length) throw new IOException("Persistent memory size mismatch for region " + id);
                targets[id] = core.retro_get_memory_data(id);
                if (size > 0 && targets[id] == null) throw new IOException("Persistent memory unavailable for region " + id);
            }
            for (int id = 0; id < 2; id++) if (values[id].length > 0)
                targets[id].write(0, values[id], 0, values[id].length);
            checkCallback();
        }
        void requireLoaded() throws IOException { if (!loaded || closed) throw new IOException("No content loaded"); }
        void checkCallback() throws IOException { if (callbackFailure != null) throw new IOException("Core callback failed: " + callbackFailure.getMessage(), callbackFailure); }

        byte environment(int command, Pointer data) {
            try {
                switch (command) {
                    case 1: return (byte) (data.getInt(0) == 0 ? 1 : 0); // Rotation not implemented.
                    case 2: data.setByte(0, (byte) 0); return 1; // No overscan crop.
                    case 3: data.setByte(0, (byte) 1); return 1; // Duplicate frames supported.
                    case 6: case 11: case 18: case 35: case 36: return 1; // Benign metadata/notification.
                    case 7: throw new IOException("Core requested shutdown");
                    case 9: case 30: case 31: data.setPointer(0, string(work.toString())); return 1;
                    case 10: {
                        int format = data.getInt(0);
                        if (format < 0 || format > 2) return 0;
                        pixelFormat = format; return 1;
                    }
                    case 15: {
                        String key = nativeText(data.getPointer(0), 129, "option key");
                        String value = options.getOrDefault(key, defaults.get(key));
                        if (value == null) return 0;
                        data.setPointer(Native.POINTER_SIZE, string(value)); return 1;
                    }
                    case 16: {
                        for (int i = 0; i < MAX_OPTIONS; i++) {
                            Pointer option = data.share((long) i * Native.POINTER_SIZE * 2), keyPointer = option.getPointer(0);
                            if (keyPointer == null) return 1;
                            String key = nativeText(keyPointer, 129, "option key");
                            String description = nativeText(option.getPointer(Native.POINTER_SIZE), 8192, "option description");
                            int separator = description.indexOf(';');
                            if (separator < 0) throw new IOException("Malformed core option");
                            String[] values = description.substring(separator + 1).trim().split("\\|", -1);
                            offeredOptions.put(key, values);
                            String pin = options.get(key);
                            if (pin != null && Arrays.stream(values).noneMatch(pin::equals)) throw new IOException("Unsupported pinned option value");
                            defaults.put(key, values[0]);
                        }
                        throw new IOException("Too many core options");
                    }
                    case 17: data.setByte(0, (byte) 0); return 1;
                    case 32: { AvInfo value = new AvInfo(); value.getPointer().write(0, data.getByteArray(0, value.size()), 0, value.size()); value.read(); av(value); return 1; }
                    case 37: { Geometry value = new Geometry(); value.getPointer().write(0, data.getByteArray(0, value.size()), 0, value.size()); value.read(); geometry(value, false); return 1; }
                    case 39: case 52: data.setInt(0, 0); return 1; // English; options-v0 only, core may fall back.
                    case 0x10033: return 1; // RetroPad bitmask query.
                    case 65: return 1; // Content overrides use persistent extended game info.
                    case 66: if (extendedGame == null) return 0; data.setPointer(0, extendedGame.getPointer()); return 1;
                    default: return 0; // No hardware rendering, VFS, BIOS fetch, analog, or netpacket API.
                }
            } catch (Exception error) { callbackFailure = error; return 0; }
        }

        void video(Pointer data, int w, int h, long pitch) {
            try {
                if (data == null) return; // Keep cached frame exactly as produced, including geometry.
                if (Pointer.nativeValue(data) == -1L) throw new IOException("Hardware video is unsupported");
                validateGeometry(w, h);
                if (maxWidth > 0 && (w > maxWidth || h > maxHeight)) throw new IOException("Frame exceeds declared maximum geometry");
                int bytesPerPixel = pixelFormat == 1 ? 4 : 2;
                if (pitch < (long) w * bytesPerPixel || pitch > (long) MAX_DIMENSION * 16 || pitch * h > 256L * 1024 * 1024) throw new IOException("Video pitch exceeds limits");
                allocateFrame(w, h); duplicate = false;
                if (row32.length < w) row32 = new int[w];
                if (row16.length < w) row16 = new short[w];
                for (int y = 0; y < h; y++) {
                    if (pixelFormat == 1) data.read(y * pitch, row32, 0, w); else data.read(y * pitch, row16, 0, w);
                    for (int x = 0; x < w; x++) {
                        int r, g, b;
                        if (pixelFormat == 1) { int value = row32[x]; r = (value >>> 16) & 255; g = (value >>> 8) & 255; b = value & 255; }
                        else {
                            int value = row16[x] & 65535, greenBits = pixelFormat == 2 ? 6 : 5;
                            r = (value >>> (5 + greenBits)) & 31; g = (value >>> 5) & ((1 << greenBits) - 1); b = value & 31;
                            r = (r << 3) | (r >>> 2); g = greenBits == 6 ? (g << 2) | (g >>> 4) : (g << 3) | (g >>> 2); b = (b << 3) | (b >>> 2);
                        }
                        int at = (y * w + x) * 4; frame[at] = (byte) r; frame[at + 1] = (byte) g; frame[at + 2] = (byte) b; frame[at + 3] = (byte) 255;
                    }
                }
            } catch (Exception error) { callbackFailure = error; }
        }
        void sample(short left, short right) throws IOException {
            if (!collectAudio) return;
            if (audioCount + 2 > samples.length) throw new IOException("Audio output exceeds run limit");
            samples[audioCount++] = left; samples[audioCount++] = right;
        }
        long audioBatch(Pointer data, long frames) {
            try {
                if (frames < 0 || frames > MAX_AUDIO_FRAMES) throw new IOException("Audio callback exceeds limits");
                if (!collectAudio || frames == 0) return frames;
                if (data == null || audioCount + frames * 2 > samples.length) throw new IOException("Audio output exceeds run limit");
                int count = (int) frames * 2;
                if (pcm.length < count) pcm = new short[count];
                data.read(0, pcm, 0, count); System.arraycopy(pcm, 0, samples, audioCount, count); audioCount += count;
                return frames;
            } catch (Exception error) { callbackFailure = error; return 0; }
        }
        short input(int port, int device, int index, int id) {
            if (index != 0) return 0;
            if ((device & 255) == 1 && port >= 0 && port < pads.length && (devices[port] & 255) == 1) {
                return id == 256 ? (short) pads[port] : id >= 0 && id < 16 ? (short) ((pads[port] >>> id) & 1) : 0;
            }
            // Mesen-specific glue selected explicitly by a trusted descriptor, not core auto-detection.
            if (gunMode == 1 && port == 0) {
                if (device == 6 && id == 0) return (short) (((gun & 255) * 65536 / 256) + 128 - 32768);
                if (device == 6 && id == 1) return (short) ((((gun >>> 8) & 255) * 65536 / 240) + 136 - 32768);
                if (device == 2 && id == 2) return (short) ((gun >>> 17) & 1);
                if (device == 2 && id == 3) return (short) ((gun >>> 16) & 1);
            }
            return 0;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            try { if (loaded) { core.retro_unload_game(); loaded = false; } }
            finally {
                try { if (initialized) { core.retro_deinit(); initialized = false; } }
                finally {
                    for (Memory value : strings.values()) value.close(); strings.clear();
                    if (romMemory != null) { romMemory.close(); romMemory = null; }
                    Arrays.fill(samples, (short) 0); Arrays.fill(pcm, (short) 0);
                }
            }
        }
    }
}
