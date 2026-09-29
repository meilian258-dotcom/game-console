package cn.piq.fcarcade.libretro.worker;

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

/** Private child JVM entry point. Native code is never loaded into Minecraft's JVM. */
public final class MesenWorker {
    public static final int MAGIC = 0x504c5231, VERSION = 1, ACK = 0x4f4b4159;
    public static final int LOAD = 1, STEP = 2, SAVE = 3, RESTORE = 4, RESET = 5, CLOSE = 6;
    public static final int MAX_ROM = 64 * 1024 * 1024, MAX_STATE = 16 * 1024 * 1024;
    public static final int RGBA_BYTES = 256 * 240 * 4, RAM_BYTES = 2048, MAX_SAMPLES = 4096;

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

    private MesenWorker() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4 || Native.POINTER_SIZE != 8) {
            throw new IllegalArgumentException("Expected private 64-bit worker arguments");
        }
        int port = Integer.parseInt(arguments[0]);
        String token = arguments[1];
        if (port < 1 || port > 65535 || !token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Invalid worker endpoint");
        }
        Path corePath = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path work = Path.of(arguments[3]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(corePath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(work.resolve("content.nes"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid isolated core/work directory");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15_000);
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(token);
                output.flush();
                if (input.readInt() != ACK) throw new IOException("Worker authentication rejected");
                socket.setSoTimeout(0); // Paused sessions may remain idle; parent bounds each operation.
                // Native stdout/stderr cannot contaminate this authenticated binary socket.
                Retro core = Native.load(corePath.toString(), Retro.class,
                        Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                if (core.retro_api_version() != 1) throw new IOException("Unsupported libretro ABI");
                try (Engine engine = new Engine(core, work)) {
                    commands(engine, input, output);
                }
            }
        }
    }

    private static void commands(Engine engine, DataInputStream input, DataOutputStream output) throws IOException {
        while (true) {
            final int command;
            try { command = input.readInt(); }
            catch (EOFException disconnected) { return; }
            try {
                switch (command) {
                    case LOAD -> {
                        boolean zapper = input.readBoolean();
                        byte[] rom = readBytes(input, 16, MAX_ROM, "ROM");
                        engine.load(rom, zapper);
                        output.writeBoolean(true);
                        output.writeUTF(engine.name);
                        output.writeUTF(engine.version);
                        output.writeDouble(engine.fps);
                        output.writeDouble(engine.sampleRate);
                    }
                    case STEP -> {
                        int one = input.readInt(), two = input.readInt(), zapper = input.readInt();
                        engine.step(one, two, zapper);
                        output.writeBoolean(true);
                        output.writeInt(RGBA_BYTES);
                        output.write(engine.frame);
                        output.writeInt(engine.audioCount);
                        for (int i = 0; i < engine.audioCount; i++) output.writeFloat(engine.samples[i]);
                        output.writeInt(RAM_BYTES);
                        output.write(engine.ram);
                    }
                    case SAVE -> {
                        byte[] state = engine.save();
                        output.writeBoolean(true);
                        output.writeInt(state.length);
                        output.write(state);
                    }
                    case RESTORE -> {
                        engine.restore(readBytes(input, 1, MAX_STATE, "state"));
                        output.writeBoolean(true);
                    }
                    case RESET -> { engine.reset(); output.writeBoolean(true); }
                    case CLOSE -> {
                        engine.close();
                        output.writeBoolean(true);
                        output.flush();
                        return;
                    }
                    default -> throw new IOException("Unknown libretro worker command");
                }
                output.flush();
            } catch (Exception failure) {
                // Every command computes its result before writing its success header.
                String message = failure.getMessage();
                if (message == null) message = failure.getClass().getSimpleName();
                output.writeBoolean(false);
                output.writeUTF(message.substring(0, Math.min(512, message.length())));
                output.flush();
                return; // Fail closed: no partially-mutated emulator may continue.
            }
        }
    }

    private static byte[] readBytes(DataInputStream input, int minimum, int maximum, String kind) throws IOException {
        int length = input.readInt();
        if (length < minimum || length > maximum) throw new IOException("Invalid " + kind + " length");
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }

    static final class Engine implements AutoCloseable {
        final Retro core;
        final Path work;
        final Map<String, Memory> strings = new HashMap<>();
        final Map<String, String> options = new HashMap<>();
        final byte[] frame = new byte[RGBA_BYTES], ram = new byte[RAM_BYTES];
        final int[] pixelRow = new int[256];
        final short[] pcm = new short[MAX_SAMPLES * 2];
        final float[] samples = new float[MAX_SAMPLES];
        final Environment environment = this::environment;
        final Video video = this::video;
        final Audio audio = (left, right) -> {
            try { sample(left, right); } catch (Exception error) { callbackFailure = error; }
        };
        final AudioBatch batch = this::audioBatch;
        final InputPoll poll = () -> {};
        final InputState input = this::input;
        volatile Exception callbackFailure;
        int audioCount, p1, p2, zapperState = 1 << 16;
        boolean initialized, loaded, closed, zapper;
        String name, version;
        double fps, sampleRate;
        Memory romMemory;
        ExtendedGameInfo extendedGame;

        Engine(Retro core, Path work) {
            this.core = core;
            this.work = work;
            for (int i = 3; i < frame.length; i += 4) frame[i] = (byte) 255;
            core.retro_set_environment(environment);
        }

        Memory string(String value) {
            return strings.computeIfAbsent(value, key -> {
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                Memory result = new Memory(bytes.length + 1L);
                result.write(0, bytes, 0, bytes.length);
                result.setByte(bytes.length, (byte) 0);
                return result;
            });
        }

        void load(byte[] rom, boolean useZapper) throws IOException {
            if (initialized || loaded || closed) throw new IOException("ROM already loaded");
            if (rom[0] != 'N' || rom[1] != 'E' || rom[2] != 'S' || rom[3] != 0x1a) {
                throw new IOException("Only iNES cartridge content is accepted");
            }
            Path path = work.resolve("content.nes");
            Files.write(path, rom, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // Mesen's fallback opens paths through std::ifstream, which fails on some Windows
            // Unicode directories. Standard extended content info supplies the same bytes directly.
            romMemory = new Memory(rom.length);
            romMemory.write(0, rom, 0, rom.length);
            extendedGame = new ExtendedGameInfo();
            extendedGame.fullPath = string(path.toString());
            extendedGame.directory = string(work.toString());
            extendedGame.name = string("content");
            extendedGame.extension = string("nes");
            extendedGame.data = romMemory;
            extendedGame.size = rom.length;
            extendedGame.persistent = 1;
            extendedGame.write();
            SystemInfo information = new SystemInfo();
            core.retro_get_system_info(information);
            information.read();
            name = information.libraryName.getString(0, "UTF-8");
            version = information.libraryVersion.getString(0, "UTF-8");
            if (!name.equals("Mesen") || version.length() > 128) throw new IOException("Unexpected emulator core");
            core.retro_init();
            initialized = true;
            checkCallback();
            // Mesen's setters dereference Console/KeyManager, which are created by retro_init.
            core.retro_set_video_refresh(video);
            core.retro_set_audio_sample(audio);
            core.retro_set_audio_sample_batch(batch);
            core.retro_set_input_poll(poll);
            core.retro_set_input_state(input);
            GameInfo game = new GameInfo();
            game.path = string(path.toString());
            game.write();
            loaded = core.retro_load_game(game) != 0;
            checkCallback();
            if (!loaded) throw new IOException("Mesen rejected this cartridge");
            zapper = useZapper;
            // Explicit subtypes override Mesen's game database peripheral auto-selection.
            core.retro_set_controller_port_device(0, 257);
            core.retro_set_controller_port_device(1, useZapper ? 262 : 257);
            for (int port = 2; port < 5; port++) core.retro_set_controller_port_device(port, 0);
            AvInfo av = new AvInfo();
            core.retro_get_system_av_info(av);
            av.read();
            timing(av.timing.fps, av.timing.sampleRate);
            if (av.geometry.baseWidth != 256 || av.geometry.baseHeight != 240) {
                throw new IOException("Unexpected Mesen frame geometry");
            }
            readRam();
            checkCallback();
        }

        void step(int one, int two, int gun) throws IOException {
            requireLoaded();
            if ((one & ~255) != 0 || (two & ~255) != 0 || (gun & ~0x3ffff) != 0
                    || ((gun >>> 8) & 255) >= 240 || ((gun & (1 << 16)) != 0 && (gun & 65535) != 0)) {
                throw new IOException("Invalid controller input");
            }
            p1 = one; p2 = two; zapperState = gun;
            audioCount = 0;
            core.retro_run();
            checkCallback();
            readRam();
        }

        byte[] save() throws IOException {
            requireLoaded();
            long size = core.retro_serialize_size();
            if (size < 1 || size > MAX_STATE) throw new IOException("Core state exceeds limits");
            try (Memory memory = new Memory(size)) {
                if (core.retro_serialize(memory, size) == 0) throw new IOException("Core could not save state");
                checkCallback();
                return memory.getByteArray(0, (int) size);
            }
        }

        void restore(byte[] state) throws IOException {
            requireLoaded();
            if (state.length != core.retro_serialize_size()) throw new IOException("State/core size mismatch");
            try (Memory memory = new Memory(state.length)) {
                memory.write(0, state, 0, state.length);
                if (core.retro_unserialize(memory, state.length) == 0) throw new IOException("Core rejected state");
            }
            audioCount = 0;
            checkCallback();
            readRam();
        }

        void reset() throws IOException {
            requireLoaded();
            core.retro_reset();
            audioCount = 0;
            checkCallback();
            readRam();
        }

        void readRam() throws IOException {
            long size = core.retro_get_memory_size(2);
            Pointer memory = core.retro_get_memory_data(2);
            if (size != RAM_BYTES || memory == null) throw new IOException("Core CPU RAM unavailable");
            memory.read(0, ram, 0, RAM_BYTES);
        }

        void requireLoaded() throws IOException {
            if (!loaded || closed) throw new IOException("No cartridge loaded");
        }

        void checkCallback() throws IOException {
            if (callbackFailure != null) throw new IOException("Mesen callback failed: " + callbackFailure.getMessage(), callbackFailure);
        }

        void timing(double newFps, double newRate) throws IOException {
            if (!Double.isFinite(newFps) || newFps < 60 || newFps > 61 || newRate != 44100) {
                throw new IOException("Unexpected Mesen NTSC/audio timing");
            }
            fps = newFps;
            sampleRate = newRate;
        }

        byte environment(int command, Pointer data) {
            try {
                switch (command) {
                    case 1: return (byte) (data.getInt(0) == 0 ? 1 : 0);
                    case 2: data.setByte(0, (byte) 0); return 1;
                    case 3: data.setByte(0, (byte) 1); return 1;
                    case 6: case 8: case 11: case 18: case 35: case 36: return 1;
                    case 9: case 30: case 31: data.setPointer(0, string(work.toString())); return 1;
                    case 10: return (byte) (data.getInt(0) == 1 ? 1 : 0);
                    case 15: {
                        String key = data.getPointer(0).getString(0, "UTF-8");
                        String value = pinnedOption(key, options.get(key));
                        if (value == null) return 0;
                        data.setPointer(Native.POINTER_SIZE, string(value));
                        return 1;
                    }
                    case 16: {
                        for (int index = 0; index < 256; index++) {
                            Pointer option = data.share((long) index * Native.POINTER_SIZE * 2);
                            Pointer key = option.getPointer(0);
                            if (key == null) return 1;
                            String description = option.getPointer(Native.POINTER_SIZE).getString(0, "UTF-8");
                            int separator = description.indexOf(';');
                            if (separator < 0) throw new IOException("Malformed core option");
                            String values = description.substring(separator + 1).trim();
                            options.put(key.getString(0, "UTF-8"), values.split("\\|", 2)[0]);
                        }
                        throw new IOException("Too many core options");
                    }
                    case 17: data.setByte(0, (byte) 0); return 1;
                    case 32: timing(data.getDouble(24), data.getDouble(32)); return 1;
                    case 37: return 1;
                    case 39: case 52: data.setInt(0, 0); return 1;
                    case 0x10033: return 1; // RETRO_ENVIRONMENT_GET_INPUT_BITMASKS
                    case 65: return 1; // Both content override and extended information supported.
                    case 66:
                        if (extendedGame == null) return 0;
                        data.setPointer(0, extendedGame.getPointer());
                        return 1;
                    default: return 0; // No hardware rendering, VFS, firmware or unmanaged callbacks.
                }
            } catch (Exception failure) { callbackFailure = failure; return 0; }
        }

        static String pinnedOption(String key, String fallback) {
            return switch (key) {
                case "mesen_region" -> "NTSC";
                case "mesen_ramstate" -> "All 0s (Default)";
                case "mesen_audio_sample_rate" -> "44100";
                case "mesen_ntsc_filter" -> "Disabled";
                case "mesen_overscan_left", "mesen_overscan_right", "mesen_overscan_up", "mesen_overscan_down" -> "None";
                case "mesen_hdpacks", "mesen_nospritelimit", "mesen_fake_stereo", "mesen_swap_duty_cycle",
                     "mesen_disable_noise_mode_flag", "mesen_shift_buttons_clockwise", "mesen_fdsfastforwardload",
                     "mesen_fdsautoinsertdisk" -> "disabled";
                case "mesen_overclock" -> "None";
                case "mesen_screenrotation" -> "None";
                case "mesen_controllerturbospeed" -> "Disabled";
                default -> fallback;
            };
        }

        void video(Pointer data, int width, int height, long pitch) {
            try {
                if (data == null) return; // Core requests reuse of previous frame.
                if (width != 256 || height != 240 || pitch < 1024 || pitch > 8192) {
                    throw new IOException("Frame geometry outside pinned profile");
                }
                for (int y = 0; y < 240; y++) {
                    data.read(y * pitch, pixelRow, 0, 256);
                    for (int x = 0; x < 256; x++) {
                        int pixel = pixelRow[x], at = (y * 256 + x) * 4;
                        frame[at] = (byte) (pixel >>> 16);
                        frame[at + 1] = (byte) (pixel >>> 8);
                        frame[at + 2] = (byte) pixel;
                        frame[at + 3] = (byte) 255;
                    }
                }
            } catch (Exception failure) { callbackFailure = failure; }
        }

        void sample(short left, short right) throws IOException {
            if (audioCount >= MAX_SAMPLES) throw new IOException("Audio output exceeds frame limit");
            samples[audioCount++] = (left + right) / 65536f;
        }

        long audioBatch(Pointer data, long count) {
            try {
                if (count < 0 || count > MAX_SAMPLES || audioCount + count > MAX_SAMPLES) {
                    throw new IOException("Audio batch exceeds frame limit");
                }
                if (count > 0) {
                    if (data == null) throw new IOException("Null audio batch");
                    data.read(0, pcm, 0, (int) count * 2);
                    for (int i = 0; i < count; i++) sample(pcm[i * 2], pcm[i * 2 + 1]);
                }
                return count;
            } catch (Exception failure) { callbackFailure = failure; return 0; }
        }

        short input(int port, int device, int index, int id) {
            if (index != 0) return 0;
            if (device == 1 && port >= 0 && port < 2) {
                if (zapper && port == 1) return 0;
                int buttons = port == 0 ? p1 : p2;
                int retro = (buttons & 0xfc) | ((buttons & 1) << 8) | ((buttons & 2) >>> 1);
                return id == 256 ? (short) retro : (id >= 0 && id < 16 ? (short) ((retro >>> id) & 1) : 0);
            }
            // Mesen's Zapper samples the global pointer and left/right mouse buttons on port 0.
            if (zapper && port == 0) {
                if (device == 6) {
                    if (id == 0) return (short) (((zapperState & 255) * 65536 / 256) + 128 - 32768);
                    if (id == 1) return (short) ((((zapperState >>> 8) & 255) * 65536 / 240) + 136 - 32768);
                }
                if (device == 2 && id == 2) return (short) ((zapperState >>> 17) & 1);
                if (device == 2 && id == 3) return (short) ((zapperState >>> 16) & 1);
            }
            return 0;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (loaded) { core.retro_unload_game(); loaded = false; }
            if (initialized) { core.retro_deinit(); initialized = false; }
            for (Memory memory : strings.values()) memory.close();
            strings.clear();
            if (romMemory != null) { romMemory.close(); romMemory = null; }
            Arrays.fill(pcm, (short) 0);
        }
    }
}
