package cn.piq.fcarcade.libretro.worker;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static cn.piq.fcarcade.libretro.worker.MesenWorker.*;

/** Standalone protocol and real-core probe. Generates its own original diagnostic cartridge. */
public final class WorkerProbe {
    private static int checks;
    private static final String TOKEN = "fc62probe0123456789abcdefghijklmnop";
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("helper.jar jna.jar core work-root");
        Path root = Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(root);
        byte[] rom = diagnosticRom();
        long started = System.nanoTime();
        try (Child one = new Child(args, root); Child two = new Child(args, root)) {
            one.load(rom, false); two.load(rom, false);
            boolean signal = false, color = false;
            for (int i = 0; i < 120; i++) {
                Frame a = one.step(i, 255 - i, 65536), b = two.step(i, 255 - i, 65536);
                check(Arrays.equals(a.ram, b.ram), "fresh processes RAM determinism");
                check(Arrays.equals(a.video, b.video), "fresh processes video determinism");
                check(Arrays.equals(a.audio, b.audio), "fresh processes audio determinism");
                for (float v : a.audio) if (Math.abs(v) > 0.001f) signal = true;
                for (int p = 0; p < a.video.length; p += 4) if ((a.video[p] | a.video[p + 1] | a.video[p + 2]) != 0) color = true;
            }
            check(signal, "audible pulse waveform produced");
            check(color, "nonblack color frame produced");
            Frame buttons = one.step(0xa5, 0x5a, 65536);
            buttons = one.step(0xa5, 0x5a, 65536);
            check(mask(buttons.ram, 16) == 0xa5, "P1 all eight buttons mapped");
            check(mask(buttons.ram, 32) == 0x5a, "P2 all eight buttons mapped");
            byte[] state = one.save();
            check(state.length > 0 && state.length < MAX_STATE, "bounded serialized state");
            for (int i = 0; i < 10; i++) one.step(0, 0, 65536);
            one.restore(state); two.restore(state);
            int audioMismatch = 0;
            for (int i = 0; i < 60; i++) {
                Frame a = one.step(i, i ^ 255, 65536), b = two.step(i, i ^ 255, 65536);
                check(Arrays.equals(a.ram, b.ram), "restored cross-process RAM determinism");
                check(Arrays.equals(a.video, b.video), "restored cross-process video determinism");
                if (!Arrays.equals(a.audio, b.audio)) audioMismatch++;
            }
            System.out.println("stateBytes=" + state.length + " restoredAudioMismatchFrames=" + audioMismatch);
            one.reset();
            for (int i = 0; i < 5; i++) one.step(0, 0, 65536);
        }
        try (Child gun = new Child(args, root)) {
            gun.load(rom, true);
            for (int i = 0; i < 10; i++) gun.step(0, 0, 65536);
            Frame released = gun.step(0, 0, 65536);
            Frame pulled = gun.step(0, 0, 65536 | (1 << 17));
            pulled = gun.step(0, 0, 65536 | (1 << 17));
            check((released.ram[48] & 16) == 0, "Zapper released");
            check((pulled.ram[48] & 16) != 0, "Zapper trigger");
            check((pulled.ram[48] & 8) != 0, "Zapper offscreen has no light");
            check(pulled.ram[49] == 0, "Offscreen never detects light across full frame");
            Frame lit = pulled;
            for (int i = 0; i < 10; i++) lit = gun.step(0, 0, (120 << 8) | 128);
            check(lit.ram[49] == 1, "Onscreen center detects rendered white light");
            gun.restore(gun.save());
        }
        try (Child malformed = new Child(args, root)) {
            malformed.load(rom, false);
            malformed.output.writeInt(STEP); malformed.output.writeInt(256);
            malformed.output.writeInt(0); malformed.output.writeInt(65536); malformed.output.flush();
            check(!malformed.input.readBoolean(), "invalid controller rejected");
            check(!malformed.input.readUTF().isEmpty(), "bounded error detail");
        }
        try (Child malformed = new Child(args, root)) {
            malformed.output.writeInt(LOAD); malformed.output.writeBoolean(false);
            malformed.output.writeInt(MAX_ROM + 1); malformed.output.flush();
            check(!malformed.input.readBoolean(), "oversized ROM rejected before allocation");
            check(malformed.input.readUTF().contains("length"), "oversized ROM error");
        }
        try (Child malformed = new Child(args, root)) {
            malformed.load(rom, false);
            malformed.output.writeInt(RESTORE); malformed.output.writeInt(MAX_STATE + 1); malformed.output.flush();
            check(!malformed.input.readBoolean(), "oversized state rejected before allocation");
            check(malformed.input.readUTF().contains("length"), "oversized state error");
        }
        try (Child paused = new Child(args, root)) {
            paused.load(rom, false);
            Thread.sleep(16_000);
            paused.step(0, 0, 65536);
            check(paused.process.isAlive(), "pause beyond previous 15s timeout remains usable");
        }
        System.out.println("PASS checks=" + checks + " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000));
    }

    static int mask(byte[] ram, int at) { int mask = 0; for (int i = 0; i < 8; i++) mask |= (ram[at + i] & 1) << i; return mask; }
    static void check(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }
    record Frame(byte[] video, float[] audio, byte[] ram) {}

    static final class Child implements AutoCloseable {
        final Process process;
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        boolean finished;
        Child(String[] args, Path root) throws Exception {
            Path work = Files.createTempDirectory(root, "worker-");
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                server.setSoTimeout(15_000);
                String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
                if (!Files.isRegularFile(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                ProcessBuilder builder = new ProcessBuilder(java, "-Xmx192m", "-cp", args[0] + File.pathSeparator + args[1],
                        "cn.piq.fcarcade.libretro.worker.MesenWorker", Integer.toString(server.getLocalPort()), TOKEN,
                        Path.of(args[2]).toAbsolutePath().toString(), work.toString());
                builder.redirectErrorStream(true).redirectOutput(work.resolve("worker.log").toFile());
                process = builder.start();
                try {
                    socket = server.accept(); socket.setSoTimeout(15_000); socket.setTcpNoDelay(true);
                    input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
                    output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                    check(input.readInt() == MAGIC && input.readInt() == VERSION && input.readUTF().equals(TOKEN), "authenticated worker hello");
                    output.writeInt(ACK); output.flush();
                } catch (Throwable failure) { process.destroyForcibly(); throw failure; }
            }
        }
        void okay() throws IOException { if (!input.readBoolean()) throw new IOException(input.readUTF()); }
        void load(byte[] rom, boolean gun) throws IOException {
            output.writeInt(LOAD); output.writeBoolean(gun); output.writeInt(rom.length); output.write(rom); output.flush();
            okay();
            System.out.println("core=" + input.readUTF() + " version=" + input.readUTF() + " fps=" + input.readDouble() + " rate=" + input.readDouble());
        }
        Frame step(int one, int two, int gun) throws IOException {
            output.writeInt(STEP); output.writeInt(one); output.writeInt(two); output.writeInt(gun); output.flush(); okay();
            check(input.readInt() == RGBA_BYTES, "RGBA length"); byte[] video = input.readNBytes(RGBA_BYTES);
            int size = input.readInt(); check(size >= 0 && size <= MAX_SAMPLES, "audio length");
            float[] audio = new float[size]; for (int i = 0; i < size; i++) audio[i] = input.readFloat();
            check(input.readInt() == RAM_BYTES, "RAM length"); byte[] ram = input.readNBytes(RAM_BYTES);
            return new Frame(video, audio, ram);
        }
        byte[] save() throws IOException { output.writeInt(SAVE); output.flush(); okay(); int n = input.readInt(); check(n > 0 && n <= MAX_STATE, "wire state size"); return input.readNBytes(n); }
        void restore(byte[] state) throws IOException { output.writeInt(RESTORE); output.writeInt(state.length); output.write(state); output.flush(); okay(); }
        void reset() throws IOException { output.writeInt(RESET); output.flush(); okay(); }
        public void close() throws Exception {
            try { if (process.isAlive()) { output.writeInt(CLOSE); output.flush(); okay(); } }
            catch (IOException alreadyFailed) { /* Child failed closed, still reap only this owned process. */ }
            finally { socket.close(); if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }

    static byte[] diagnosticRom() {
        byte[] rom = new byte[16 + 16384 + 8192];
        rom[0] = 'N'; rom[1] = 'E'; rom[2] = 'S'; rom[3] = 26; rom[4] = 1; rom[5] = 1;
        Code c = new Code();
        c.bytes(0x78, 0xd8, 0xa2, 0xff, 0x9a, 0xe8, 0x8e, 0, 0x20, 0x8e, 1, 0x20, 0x8e, 0x10, 0x40);
        c.label("warm1"); c.bytes(0x2c, 2, 0x20); c.branch(0x10, "warm1");
        c.label("warm2"); c.bytes(0x2c, 2, 0x20); c.branch(0x10, "warm2");
        c.write(0x2006, 0x3f); c.write(0x2006, 0); c.write(0x2007, 0x30); c.write(0x2001, 8);
        c.write(0x4015, 1); c.write(0x4000, 0xbf); c.write(0x4001, 0); c.write(0x4002, 0xfd); c.write(0x4003, 8);
        c.label("frame");
        c.bytes(0xad, 0x17, 0x40, 0x85, 0x30, 0x29, 8); c.branch(0xd0, "dark");
        c.bytes(0xa9, 1, 0x85, 0x31);
        c.label("dark"); c.bytes(0x2c, 2, 0x20); c.branch(0x10, "frame");
        c.bytes(0xe6, 0); c.write(0x4016, 1); c.write(0x4016, 0); c.bytes(0xa2, 0);
        c.label("pad");
        c.bytes(0xad, 0x16, 0x40, 0x29, 1, 0x95, 0x10, 0xad, 0x17, 0x40, 0x29, 1, 0x95, 0x20, 0xe8, 0xe0, 8);
        c.branch(0xd0, "pad"); c.bytes(0xad, 0x17, 0x40, 0x85, 0x30); c.jump("frame");
        byte[] program = c.finish(); System.arraycopy(program, 0, rom, 16, program.length);
        for (int i = 0; i < 3; i++) { rom[16 + 16384 - 6 + i * 2] = 0; rom[16 + 16384 - 5 + i * 2] = (byte) 0x80; }
        return rom;
    }
    static final class Code {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Map<String, Integer> labels = new HashMap<>();
        final List<int[]> fixups = new ArrayList<>(); final List<String> names = new ArrayList<>();
        void bytes(int... values) { for (int value : values) bytes.write(value); }
        void label(String name) { labels.put(name, bytes.size()); }
        void write(int address, int value) { bytes(0xa9, value, 0x8d, address & 255, address >>> 8); }
        void branch(int opcode, String name) { bytes(opcode); fixups.add(new int[]{bytes.size(), 1}); names.add(name); bytes(0); }
        void jump(String name) { bytes(0x4c); fixups.add(new int[]{bytes.size(), 2}); names.add(name); bytes(0, 0); }
        byte[] finish() {
            byte[] result = bytes.toByteArray();
            for (int i = 0; i < fixups.size(); i++) {
                int[] fix = fixups.get(i); int target = labels.get(names.get(i));
                if (fix[1] == 1) { int delta = target - fix[0] - 1; if (delta < -128 || delta > 127) throw new AssertionError(); result[fix[0]] = (byte) delta; }
                else { result[fix[0]] = (byte) target; result[fix[0] + 1] = (byte) (0x80 + (target >>> 8)); }
            }
            return result;
        }
    }
}
