package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.session.NesCoreVariant;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Real-core compatibility gate, not a Minecraft/multiplayer acceptance test.
 * Uses only an original diagnostic NROM and temporary directories owned by each core.
 * An optional, SHA-pinned FC63 JAR is loaded independently to avoid comparing the new
 * implementation with itself. No ROM or save from a player's instance is accessed.
 */
public final class GenericLibretroCompatibilitySmoke {
    private static final String FC63_JAR_SHA = "ea76d5983b26815509131197855e2116b7d1af9fa77d0b1dfd3cb257e4c4c5dd";
    private static final String PROFILE_SHA = "2b5deb2b15474ad01e933ff31e3c84df03c99163bb3f05ed8d705de379d2e575";
    private static final String PROFILE_RESOURCE = "/core/libretro/mesen-profile.properties";
    private static int checks, comparedFrames;
    private static boolean heardAudio, sawColor;

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Optional argument: frozen FC63 JAR");
        long started = System.nanoTime();
        try (Baseline baseline = new Baseline(args.length == 1 ? Path.of(args[0]) : null)) {
            verifyIdentityResources();
            byte[] normal = compareMode(baseline, false);
            byte[] gun = compareMode(baseline, true);
            rejectCrossMode(normal, gun);
            check(heardAudio, "diagnostic ROM generated a nonzero waveform");
            check(sawColor, "diagnostic ROM generated nonblack pixels");
            System.out.println("PASS generic-libretro FC63 compatibility checks=" + checks
                    + " comparedFrames=" + comparedFrames + " baseline=" + baseline.description
                    + " profile=" + PROFILE_SHA + " normalStateBytes=" + normal.length
                    + " zapperStateBytes=" + gun.length + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static void verifyIdentityResources() throws Exception {
        check(LibretroNesCore.PROFILE_SHA256.equals(PROFILE_SHA), "FC63 profile identity retained");
        for (NesCoreVariant variant : List.of(NesCoreVariant.LIBRETRO_V1, NesCoreVariant.LIBRETRO_ZAPPER_V1)) {
            String resource = NesCores.moduleResource(variant);
            check(resource.equals(PROFILE_RESOURCE), "private-play module resource stays FC63 profile");
            try (InputStream in = NesCores.class.getResourceAsStream(resource)) {
                check(in != null, "private-play profile available");
                check(sha(in.readAllBytes()).equals(PROFILE_SHA), "private-play module bytes unchanged");
            }
        }
    }

    private static byte[] compareMode(Baseline baseline, boolean zapper) throws Exception {
        byte[] rom = diagnosticRom();
        String romSha = sha(rom), mode = zapper ? "zapper" : "joypad";
        NesCoreVariant variant = zapper ? NesCoreVariant.LIBRETRO_ZAPPER_V1 : NesCoreVariant.LIBRETRO_V1;
        try (NesCore oldCore = baseline.create(zapper); NesCore newCore = new GenericLibretroNesCore(zapper)) {
            check(oldCore.stateNamespace().equals(variant.stateNamespace()), mode + " old namespace");
            check(newCore.stateNamespace().equals(oldCore.stateNamespace()), mode + " compatible namespace");
            check(newCore.supportsZapper() == zapper, mode + " gun capability");
            oldCore.loadRom(rom); newCore.loadRom(rom);
            compareOutput(oldCore, newCore, mode + " initial caches", true);
            // The diagnostic's light latch is sticky RAM. Test darkness on a fresh
            // power-on, not after a soft reset (which need not erase NES RAM).
            if (zapper) verifyGunBehavior(oldCore, newCore);
            for (int i = 0; i < 120; i++) {
                inputs(oldCore, i, zapper); inputs(newCore, i, zapper);
                runAndCompare(oldCore, newCore, mode + " fresh frame " + i);
            }
            // Prove buttons were really delivered, not simply ignored identically.
            oldCore.setControllerState(0, 0xa5); newCore.setControllerState(0, 0xa5);
            oldCore.setControllerState(1, 0x5a); newCore.setControllerState(1, 0x5a);
            runAndCompare(oldCore, newCore, mode + " button settle 1");
            runAndCompare(oldCore, newCore, mode + " button settle 2");
            Frame buttons = frame(newCore);
            check(mask(buttons.ram, 16) == 0xa5, mode + " P1 button mapping");
            if (!zapper) check(mask(buttons.ram, 32) == 0x5a, "P2 button mapping");

            if (zapper) {
                oldCore.setZapperState(128, 120, false, true); newCore.setZapperState(128, 120, false, true);
                runAndCompare(oldCore, newCore, "save aimed gun with trigger held");
            }

            byte[] oldState = oldCore.saveTransientState();
            check(variant.acceptsStateHeader(oldState, romSha), mode + " old state accepted by server gate");
            // Move the receiver away first: a no-op restore cannot pass this test.
            for (int i = 0; i < 9; i++) { inputs(newCore, i + 210, zapper); newCore.runFrame(); }
            newCore.loadTransientState(oldState);
            compareOutput(oldCore, newCore, mode + " old->generic immediate caches", false);
            check(newCore.copyAudioSamples(new float[4096]) == 0, mode + " old->generic no audio replay");
            runAndCompareVideoRam(oldCore, newCore, mode + " old->generic saved input next frame");
            // Mesen resets un-serialized audio filter/sample phase on restore. The old->old
            // control in LibretroAudioRestoreDiagnostic proves that comparing a continuous
            // source against a restored receiver is not an audio-bit compatibility test.
            // Restore BOTH implementations before comparing every output sample strictly.
            oldCore.loadTransientState(oldState); newCore.loadTransientState(oldState);
            runAndCompare(oldCore, newCore, mode + " both restored old state, saved input next frame");
            for (int i = 0; i < 60; i++) {
                inputs(oldCore, i + 300, zapper); inputs(newCore, i + 300, zapper);
                runAndCompare(oldCore, newCore, mode + " old->generic replay " + i);
            }

            if (zapper) {
                oldCore.setZapperState(0, 0, true, true); newCore.setZapperState(0, 0, true, true);
                runAndCompare(oldCore, newCore, "save offscreen gun with trigger held");
            }

            byte[] newState = newCore.saveTransientState();
            check(variant.acceptsStateHeader(newState, romSha), mode + " generic state accepted by server gate");
            verifyEnvelope(newState, zapper, romSha);
            for (int i = 0; i < 7; i++) { inputs(oldCore, i + 410, zapper); oldCore.runFrame(); }
            oldCore.loadTransientState(newState);
            compareOutput(oldCore, newCore, mode + " generic->old immediate caches", false);
            check(oldCore.copyAudioSamples(new float[4096]) == 0, mode + " generic->old no audio replay");
            runAndCompareVideoRam(oldCore, newCore, mode + " generic->old saved input next frame");
            oldCore.loadTransientState(newState); newCore.loadTransientState(newState);
            runAndCompare(oldCore, newCore, mode + " both restored generic state, saved input next frame");
            for (int i = 0; i < 60; i++) {
                inputs(oldCore, i + 500, zapper); inputs(newCore, i + 500, zapper);
                runAndCompare(oldCore, newCore, mode + " generic->old replay " + i);
            }
            byte[] stable = newCore.saveTransientState();
            rejectMalformed(newCore, stable, zapper);
            runAndCompare(oldCore, newCore, mode + " remains aligned after rejected states");
            oldCore.reset(); newCore.reset();
            compareOutput(oldCore, newCore, mode + " reset caches", true);
            for (int i = 0; i < 12; i++) runAndCompare(oldCore, newCore, mode + " after reset " + i);
            System.out.println("mode=" + mode + " bidirectionalStateCompatibility=PASS stateBytes=" + stable.length);
            return stable;
        }
    }

    private static void verifyGunBehavior(NesCore oldCore, NesCore newCore) {
        oldCore.reset(); newCore.reset();
        oldCore.setZapperState(0, 0, true, false); newCore.setZapperState(0, 0, true, false);
        for (int i = 0; i < 12; i++) runAndCompare(oldCore, newCore, "gun offscreen released " + i);
        check((frame(newCore).ram[48] & 16) == 0, "gun trigger released");
        oldCore.setZapperState(0, 0, true, true); newCore.setZapperState(0, 0, true, true);
        for (int i = 0; i < 2; i++) runAndCompare(oldCore, newCore, "gun offscreen triggered " + i);
        Frame dark = frame(newCore);
        check((dark.ram[48] & 16) != 0, "gun trigger delivered");
        check((dark.ram[48] & 8) != 0 && dark.ram[49] == 0, "offscreen does not detect light");
        oldCore.setZapperState(128, 120, false, false); newCore.setZapperState(128, 120, false, false);
        for (int i = 0; i < 10; i++) runAndCompare(oldCore, newCore, "gun onscreen " + i);
        check(frame(newCore).ram[49] == 1, "gun detects rendered white light");
    }

    private static void rejectCrossMode(byte[] normalState, byte[] gunState) {
        try (NesCore normal = new GenericLibretroNesCore(false); NesCore gun = new GenericLibretroNesCore(true)) {
            normal.loadRom(diagnosticRom()); gun.loadRom(diagnosticRom());
            rejected(() -> normal.loadTransientState(gunState), "gun state rejected by normal core");
            rejected(() -> gun.loadTransientState(normalState), "normal state rejected by gun core");
            normal.runFrame(); gun.runFrame();
            check(frame(normal).rgba.length == NesCore.RGBA_BYTES, "normal remains usable after mode rejection");
            check(frame(gun).rgba.length == NesCore.RGBA_BYTES, "gun remains usable after mode rejection");
        }
    }

    private static void rejectMalformed(NesCore core, byte[] state, boolean zapper) {
        byte[] before = core.saveTransientState();
        List<byte[]> bad = new ArrayList<>();
        byte[] magic = state.clone(); magic[0] ^= 1; bad.add(magic);
        byte[] version = state.clone(); ByteBuffer.wrap(version).putInt(4, 2); bad.add(version);
        byte[] mode = state.clone(); ByteBuffer.wrap(mode).putInt(8, zapper ? 0 : 1); bad.add(mode);
        byte[] profile = state.clone(); profile[12] ^= 1; bad.add(profile);
        byte[] rom = state.clone(); rom[44] ^= 1; bad.add(rom);
        byte[] length = state.clone(); ByteBuffer.wrap(length).putInt(76, state.length); bad.add(length);
        byte[] nativeLength = state.clone(); ByteBuffer.wrap(nativeLength).putInt(80, -1); bad.add(nativeLength);
        byte[] controller = state.clone(); ByteBuffer.wrap(controller).putInt(state.length - 12, 256); bad.add(controller);
        byte[] gun = state.clone(); ByteBuffer.wrap(gun).putInt(state.length - 4, 0x40000); bad.add(gun);
        byte[] offscreen = state.clone(); ByteBuffer.wrap(offscreen).putInt(state.length - 4, 65537); bad.add(offscreen);
        bad.add(Arrays.copyOf(state, state.length - 1)); bad.add(new byte[0]); bad.add(null);
        for (int i = 0; i < bad.size(); i++) {
            byte[] malformed = bad.get(i);
            rejected(() -> core.loadTransientState(malformed), "malformed state rejected " + i);
        }
        check(Arrays.equals(before, core.saveTransientState()), "rejected states do not mutate native/cache/input state");
    }

    private static void verifyEnvelope(byte[] state, boolean zapper, String romSha) {
        ByteBuffer b = ByteBuffer.wrap(state);
        check(b.getInt() == 0x504c5231 && b.getInt() == 1 && b.getInt() == (zapper ? 1 : 0), "PLR1 v1 mode header");
        byte[] profile = new byte[32], rom = new byte[32]; b.get(profile); b.get(rom);
        check(HexFormat.of().formatHex(profile).equals(PROFILE_SHA), "state profile exact");
        check(HexFormat.of().formatHex(rom).equals(romSha), "state ROM identity exact");
        int payload = b.getInt(), nativeBytes = b.getInt();
        check(payload == state.length - 80 && nativeBytes > 0
                && nativeBytes + 16 + NesCore.RGBA_BYTES + NesCore.CPU_RAM_BYTES == payload, "state payload layout unchanged");
    }

    private static void inputs(NesCore core, int tick, boolean zapper) {
        core.setControllerState(0, (tick * 37 + 11) & 255);
        core.setControllerState(1, (tick * 19 + 73) & 255);
        if (zapper) {
            boolean offscreen = tick % 7 == 0;
            core.setZapperState((tick * 7) & 255, (tick * 11) % 240, offscreen, tick % 3 == 0);
        }
    }
    private static void runAndCompare(NesCore oldCore, NesCore newCore, String label) {
        oldCore.runFrame(); newCore.runFrame(); comparedFrames++;
        compareOutput(oldCore, newCore, label, true);
    }
    private static void runAndCompareVideoRam(NesCore oldCore, NesCore newCore, String label) {
        oldCore.runFrame(); newCore.runFrame(); comparedFrames++;
        compareOutput(oldCore, newCore, label, false);
    }
    private static void compareOutput(NesCore oldCore, NesCore newCore, String label, boolean audio) {
        Frame a = frame(oldCore), b = frame(newCore);
        check(Arrays.equals(a.rgba, b.rgba), label + " RGBA");
        check(Arrays.equals(a.ram, b.ram), label + " CPU RAM");
        if (audio) {
            check(a.audio.length == b.audio.length, label + " audio count " + a.audio.length + "/" + b.audio.length);
            for (int i = 0; i < a.audio.length; i++) {
                if (Float.floatToRawIntBits(a.audio[i]) != Float.floatToRawIntBits(b.audio[i]))
                    throw new AssertionError(label + " audio bits at sample " + i);
                check(Float.isFinite(a.audio[i]), label + " finite audio");
                heardAudio |= Math.abs(a.audio[i]) > .001f;
            }
            checks++;
        }
        for (int i = 0; i < a.rgba.length; i += 4) sawColor |= (a.rgba[i] | a.rgba[i + 1] | a.rgba[i + 2]) != 0;
    }
    private static Frame frame(NesCore core) {
        byte[] rgba = new byte[NesCore.RGBA_BYTES], ram = new byte[NesCore.CPU_RAM_BYTES];
        float[] audio = new float[4096]; core.copyFrameRgba(rgba); core.copyCpuRam(ram);
        return new Frame(rgba, ram, Arrays.copyOf(audio, core.copyAudioSamples(audio)));
    }
    private record Frame(byte[] rgba, byte[] ram, float[] audio) {}
    private static int mask(byte[] ram, int at) { int mask = 0; for (int i = 0; i < 8; i++) mask |= (ram[at + i] & 1) << i; return mask; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void rejected(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    /** Minimal reflection facade keeps the frozen JAR's classes/resources fully isolated. */
    private static final class Baseline implements AutoCloseable {
        final URLClassLoader loader;
        final String description;
        Baseline(Path frozenJar) throws Exception {
            if (frozenJar == null) { loader = null; description = "source-legacy-bridge"; return; }
            Path jar = frozenJar.toAbsolutePath().normalize();
            check(Files.isRegularFile(jar) && sha(Files.readAllBytes(jar)).equals(FC63_JAR_SHA), "frozen FC63 JAR exact SHA");
            loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            description = "frozen-FC63-" + FC63_JAR_SHA;
        }
        NesCore create(boolean zapper) throws Exception {
            if (loader == null) return new LibretroNesCore(zapper);
            Class<?> type = loader.loadClass("cn.piq.fcarcade.core.libretro.LibretroNesCore");
            return new FrozenCore(type.getConstructor(boolean.class).newInstance(zapper));
        }
        @Override public void close() throws Exception { if (loader != null) loader.close(); }
    }
    private static final class FrozenCore implements NesCore {
        private final Object core;
        FrozenCore(Object core) { this.core = core; }
        private Object call(String name, Class<?>[] types, Object... args) {
            try { return core.getClass().getMethod(name, types).invoke(core, args); }
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error r) throw r;
                throw new AssertionError("Frozen core call failed: " + name, e.getCause());
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        public void loadRom(byte[] rom) { call("loadRom", new Class<?>[]{byte[].class}, (Object)rom); }
        public void reset() { call("reset", new Class<?>[0]); }
        public void setControllerState(int player, int mask) { call("setControllerState", new Class<?>[]{int.class,int.class}, player,mask); }
        public boolean supportsZapper() { return (boolean)call("supportsZapper", new Class<?>[0]); }
        public void setZapperState(int x, int y, boolean offscreen, boolean trigger) { call("setZapperState", new Class<?>[]{int.class,int.class,boolean.class,boolean.class},x,y,offscreen,trigger); }
        public String stateNamespace() { return (String)call("stateNamespace", new Class<?>[0]); }
        public void runFrame() { call("runFrame", new Class<?>[0]); }
        public void copyFrameRgba(byte[] target) { call("copyFrameRgba",new Class<?>[]{byte[].class},(Object)target); }
        public int copyAudioSamples(float[] target) { return (int)call("copyAudioSamples",new Class<?>[]{float[].class},(Object)target); }
        public void copyCpuRam(byte[] target) { call("copyCpuRam",new Class<?>[]{byte[].class},(Object)target); }
        public byte[] saveTransientState() { return (byte[])call("saveTransientState",new Class<?>[0]); }
        public void loadTransientState(byte[] state) { call("loadTransientState",new Class<?>[]{byte[].class},(Object)state); }
        public void close() { call("close",new Class<?>[0]); }
    }

    /** Original white-screen/pulse/controller/Zapper diagnostic from the FC62 WorkerProbe. */
    static byte[] diagnosticRom() {
        byte[] rom = new byte[16 + 16384 + 8192];
        rom[0]='N'; rom[1]='E'; rom[2]='S'; rom[3]=26; rom[4]=1; rom[5]=1;
        Code c = new Code();
        c.bytes(0x78,0xd8,0xa2,0xff,0x9a,0xe8,0x8e,0,0x20,0x8e,1,0x20,0x8e,0x10,0x40);
        c.label("warm1"); c.bytes(0x2c,2,0x20); c.branch(0x10,"warm1");
        c.label("warm2"); c.bytes(0x2c,2,0x20); c.branch(0x10,"warm2");
        c.write(0x2006,0x3f); c.write(0x2006,0); c.write(0x2007,0x30); c.write(0x2001,8);
        c.write(0x4015,1); c.write(0x4000,0xbf); c.write(0x4001,0); c.write(0x4002,0xfd); c.write(0x4003,8);
        c.label("frame"); c.bytes(0xad,0x17,0x40,0x85,0x30,0x29,8); c.branch(0xd0,"dark");
        c.bytes(0xa9,1,0x85,0x31); c.label("dark"); c.bytes(0x2c,2,0x20); c.branch(0x10,"frame");
        c.bytes(0xe6,0); c.write(0x4016,1); c.write(0x4016,0); c.bytes(0xa2,0);
        c.label("pad"); c.bytes(0xad,0x16,0x40,0x29,1,0x95,0x10,0xad,0x17,0x40,0x29,1,0x95,0x20,0xe8,0xe0,8);
        c.branch(0xd0,"pad"); c.bytes(0xad,0x17,0x40,0x85,0x30); c.jump("frame");
        byte[] program=c.finish(); System.arraycopy(program,0,rom,16,program.length);
        for(int i=0;i<3;i++){rom[16+16384-6+i*2]=0;rom[16+16384-5+i*2]=(byte)0x80;}
        return rom;
    }
    private static final class Code {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final Map<String,Integer> labels=new HashMap<>();
        final List<int[]> fixups=new ArrayList<>(); final List<String> names=new ArrayList<>();
        void bytes(int... values){for(int value:values)bytes.write(value);}
        void label(String name){labels.put(name,bytes.size());}
        void write(int address,int value){bytes(0xa9,value,0x8d,address&255,address>>>8);}
        void branch(int opcode,String name){bytes(opcode);fixups.add(new int[]{bytes.size(),1});names.add(name);bytes(0);}
        void jump(String name){bytes(0x4c);fixups.add(new int[]{bytes.size(),2});names.add(name);bytes(0,0);}
        byte[] finish(){
            byte[] result=bytes.toByteArray();
            for(int i=0;i<fixups.size();i++){
                int[] fix=fixups.get(i);int target=labels.get(names.get(i));
                if(fix[1]==1){int delta=target-fix[0]-1;if(delta< -128||delta>127)throw new AssertionError();result[fix[0]]=(byte)delta;}
                else{result[fix[0]]=(byte)target;result[fix[0]+1]=(byte)(0x80+(target>>>8));}
            }
            return result;
        }
    }
}
