// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.retro.libretro.LibretroProcess;
import cn.piq.retro.libretro.LibretroProfile;
import cn.piq.retro.libretro.LibretroRuntime;
import cn.piq.retro.libretro.LibretroRuntimes;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

/** FC adapter over the shared software-libretro transport, retaining the tested FC62/63 state contract. */
public final class GenericLibretroNesCore implements NesCore {
    private static final String COMPATIBLE_PROFILE = "2b5deb2b15474ad01e933ff31e3c84df03c99163bb3f05ed8d705de379d2e575";
    private static final int MAGIC = 0x504c5231, MAX_STATE = 16 * 1024 * 1024;
    private static final int CACHE_BYTES = 4 * Integer.BYTES + RGBA_BYTES + CPU_RAM_BYTES;
    private final Thread owner = Thread.currentThread();
    private final boolean zapper;
    private final LibretroRuntime core;
    private final byte[] frame = new byte[RGBA_BYTES], ram = new byte[CPU_RAM_BYTES];
    private final float[] audio = new float[4096];
    private byte[] romHash;
    private int p1, p2, gun = 1 << 16, samples;
    private boolean closed;

    public GenericLibretroNesCore(boolean zapper) {
        this(zapper, LibretroRuntimes.defaultBackend(true));
    }
    /** Windows shared/server workers now use JNI; unsupported platforms retain explicit compatibility support. */
    public GenericLibretroNesCore(boolean zapper, LibretroRuntimes.Backend backend) {
        this(zapper, LibretroRuntimes.create(profile(zapper), GenericLibretroNesCore.class, backend));
    }
    /** Runtime injection keeps core semantics separate from transport and permits inert contract tests. */
    public GenericLibretroNesCore(boolean zapper, LibretroRuntime runtime) {
        if (!LibretroNesCore.PROFILE_SHA256.equals(COMPATIBLE_PROFILE))
            throw new IllegalStateException("FC compatibility profile changed; new runtime needs revalidation");
        this.zapper = zapper; core = Objects.requireNonNull(runtime);
    }
    public static LibretroProfile profile(boolean zapper) {
        Map<String, String> options = new TreeMap<>();
        options.put("mesen_region", "NTSC"); options.put("mesen_ramstate", "All 0s (Default)");
        options.put("mesen_audio_sample_rate", "44100"); options.put("mesen_ntsc_filter", "Disabled");
        for (String key : List.of("mesen_overscan_left", "mesen_overscan_right", "mesen_overscan_up", "mesen_overscan_down",
                "mesen_overclock", "mesen_screenrotation")) options.put(key, "None");
        for (String key : List.of("mesen_hdpacks", "mesen_nospritelimit", "mesen_fake_stereo", "mesen_swap_duty_cycle",
                "mesen_disable_noise_mode_flag", "mesen_shift_buttons_clockwise", "mesen_fdsfastforwardload", "mesen_fdsautoinsertdisk"))
            options.put(key, "disabled");
        options.put("mesen_controllerturbospeed", "Disabled");
        return new LibretroProfile("Mesen", "nes", true, List.of(257, zapper ? 262 : 257), zapper, options, Map.of(
                "windows-x64", new LibretroProfile.Artifact("/core/libretro/windows-x64/mesen_libretro.dll", "2b3fbe286995c80ebbc85239fd28c8fa07b1011cc69c7f9021816429e3473885"),
                "linux-x64", new LibretroProfile.Artifact("/core/libretro/linux-x64/mesen_libretro.so", "552f8ab6ac1fd08bd555f589eb999be73a469c79ccfa929f884adb2cf3366b43")));
    }
    @Override public void loadRom(byte[] rom) {
        check(); if (romHash != null) throw new IllegalStateException("ROM already loaded");
        if (rom == null || rom.length < 16 || rom.length > 32 * 1024 * 1024
                || rom[0] != 'N' || rom[1] != 'E' || rom[2] != 'S' || rom[3] != 26) throw new IllegalArgumentException("iNES ROM size/header");
        try { verify(core.load(rom)); romHash = hash(rom); }
        catch (RuntimeException error) { close(); throw error; }
    }
    private static void verify(LibretroProcess.Info info) {
        if (info.width() != WIDTH || info.height() != HEIGHT || info.fps() < 60 || info.fps() > 61 || info.sampleRate() != 44100)
            throw new IllegalStateException("Core output differs from compatible Mesen profile");
    }
    private static int retro(int nes) { return (nes & 0xfc) | ((nes & 1) << 8) | ((nes & 2) >>> 1); }
    @Override public void runFrame() {
        loaded();
        var result = core.runWithMemory(List.of(new LibretroProcess.Controls(new int[]{retro(p1), retro(p2)}, zapper ? gun : 0)),
                LibretroProcess.VIDEO | LibretroProcess.AUDIO, 2);
        verify(result.info());
        if (result.rgba().length != frame.length || result.stereo().length / 2 > audio.length) throw new IllegalStateException("FC output limit");
        System.arraycopy(result.rgba(), 0, frame, 0, frame.length);
        samples = result.stereo().length / 2;
        for (int i = 0; i < samples; i++) audio[i] = (result.stereo()[i * 2] + result.stereo()[i * 2 + 1]) / 65536f;
        byte[] memory = result.memory();
        if (memory.length != ram.length) throw new IllegalStateException("FC CPU RAM differs from state profile");
        System.arraycopy(memory, 0, ram, 0, ram.length);
    }
    @Override public void reset() {
        loaded(); verify(core.reset()); p1 = p2 = samples = 0; gun = 1 << 16; Arrays.fill(frame, (byte) 0); Arrays.fill(ram, (byte) 0);
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
    @Override public String stateNamespace() { return (zapper ? "nes-libretro-mesen-zapper-v1/" : "nes-libretro-mesen-v1/") + COMPATIBLE_PROFILE; }
    @Override public String diagnosticError() { return core.diagnosticError(); }
    @Override public void copyFrameRgba(byte[] target) { loaded(); if (target.length < frame.length) throw new IllegalArgumentException("Frame capacity"); System.arraycopy(frame, 0, target, 0, frame.length); }
    @Override public int copyAudioSamples(float[] target) { loaded(); if (target.length < samples) throw new IllegalArgumentException("Audio capacity"); System.arraycopy(audio, 0, target, 0, samples); return samples; }
    @Override public void copyCpuRam(byte[] target) { loaded(); if (target.length < ram.length) throw new IllegalArgumentException("RAM capacity"); System.arraycopy(ram, 0, target, 0, ram.length); }
    @Override public byte[] saveTransientState() {
        loaded(); byte[] state = core.serialize();
        if (state.length > MAX_STATE - CACHE_BYTES) throw new IllegalStateException("FC snapshot size");
        int size = CACHE_BYTES + state.length;
        return ByteBuffer.allocate(80 + size).putInt(MAGIC).putInt(1).putInt(zapper ? 1 : 0)
                .put(HexFormat.of().parseHex(COMPATIBLE_PROFILE)).put(romHash).putInt(size).putInt(state.length)
                .put(state).put(frame).put(ram).putInt(p1).putInt(p2).putInt(gun).array();
    }
    @Override public void loadTransientState(byte[] state) {
        ByteBuffer b = checkedTransientState(state);
        int nativeLength = b.getInt(80);
        core.restore(Arrays.copyOfRange(state, 84, 84 + nativeLength));
        b.position(84 + nativeLength); b.get(frame); b.get(ram); p1 = b.getInt(); p2 = b.getInt(); gun = b.getInt(); samples = 0;
    }
    private ByteBuffer checkedTransientState(byte[] state) {
        loaded();
        if (state == null || state.length < 81 + CACHE_BYTES || state.length > MAX_STATE + 80) throw new IllegalArgumentException("Snapshot size");
        ByteBuffer b = ByteBuffer.wrap(state);
        if (b.getInt() != MAGIC || b.getInt() != 1 || b.getInt() != (zapper ? 1 : 0)) throw new IllegalArgumentException("Different snapshot core or mode");
        byte[] identity = new byte[32], rom = new byte[32]; b.get(identity); b.get(rom); int length = b.getInt();
        if (!MessageDigest.isEqual(identity, HexFormat.of().parseHex(COMPATIBLE_PROFILE)) || !MessageDigest.isEqual(rom, romHash)
                || length != b.remaining() || length <= CACHE_BYTES) throw new IllegalArgumentException("Different snapshot profile or game");
        int nativeLength = b.getInt();
        if (nativeLength < 1 || nativeLength > MAX_STATE - CACHE_BYTES || nativeLength != length - CACHE_BYTES) throw new IllegalArgumentException("Snapshot native state size");
        int at = state.length - 12, a = b.getInt(at), c = b.getInt(at + 4), g = b.getInt(at + 8);
        boolean offscreen = (g & (1 << 16)) != 0;
        if ((a & ~255) != 0 || (c & ~255) != 0 || (g & ~0x3ffff) != 0 || offscreen && (g & 65535) != 0
                || !offscreen && ((g >>> 8) & 255) >= HEIGHT || !zapper && g != (1 << 16)) throw new IllegalArgumentException("Snapshot controller state");
        return b;
    }
    @Override public byte[] savePersistentState() {
        // Both captures occur on the owner thread without running a frame between them.
        byte[] snapshot = saveTransientState();
        var memory = core.saveMemory();
        return cn.piq.fcarcade.session.NesPersistentState.encode(snapshot, core.persistenceIdentity(), memory);
    }
    @Override public void loadPersistentState(byte[] saved) {
        loaded();
        if (!cn.piq.fcarcade.session.NesPersistentState.isBundle(saved)) { loadTransientState(saved); return; }
        var parts = cn.piq.fcarcade.session.NesPersistentState.decode(saved);
        byte[] snapshot = parts.snapshot();
        checkedTransientState(snapshot); // Validate game/profile/input BEFORE touching native memory.
        // Native data is exact-artifact scoped. A compatible cross-platform PLR1 resume
        // can still use the full snapshot; never import an unmatched standalone region.
        if (MessageDigest.isEqual(parts.identity(), core.persistenceIdentity())) core.restoreSaveMemory(parts.memory());
        loadTransientState(snapshot); // Exact resume is authoritative; never overlay SRAM afterwards.
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("NES core accessed outside owning thread");
        if (closed) throw new IllegalStateException("NES core closed");
    }
    private void loaded() { check(); if (romHash == null) throw new IllegalStateException("No ROM loaded"); }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("NES core closed outside owning thread");
        if (!closed) { core.close(); closed = true; }
    }
}
