// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.privateplay;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.rom.INesHeader;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.session.ControllerInputTransitions;
import cn.piq.fcarcade.session.NesCoreVariant;
import cn.piq.retro.libretro.LibretroRuntimes;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** One bounded, client-only NES owner worker. No server session, downloads, NBT, digest or media publisher. */
public final class FcPrivateEngine implements PrivateEngine {
    private static final Semaphore CAPACITY = new Semaphore(1);
    private static final long FRAME_NANOS = 1_000_000_000L / 60;
    private static final int MAX_FRAMES = 6;
    @FunctionalInterface interface CoreFactory { NesCore create(NesCoreVariant variant) throws Exception; }
    private final Object mailbox = new Object();
    private final ArrayDeque<CabinetFrame> frames = new ArrayDeque<>();
    private final ControllerInputTransitions[] inputs = {new ControllerInputTransitions(), new ControllerInputTransitions()};
    private final CompletableFuture<SaveResult> stopped = new CompletableFuture<>();
    private final Thread worker;
    private volatile boolean ready, stopping, paused;
    private volatile String failure;
    private volatile NesCore diagnosticCore;
    private long presentationGeneration;
    private final cn.piq.fcarcade.client.performance.FramePerformance performance = new cn.piq.fcarcade.client.performance.FramePerformance();
    private volatile long completedFrames;
    public void performanceEnabled(boolean value) { performance.enabled(value); }
    public record Performance(cn.piq.fcarcade.client.performance.FramePerformance.Sample sample, String state, long frames, int queued) {}
    public Performance performance() {
        synchronized (mailbox) {
            return new Performance(performance.sample(),failure!=null?"运行失败":stopping?"已停止":!ready?"加载中":paused?"已暂停":"运行中",completedFrames,frames.size());
        }
    }

    /** Construction only allocates bounded mailboxes and starts a daemon; all disk/core work is off-thread. */
    public FcPrivateEngine(Path localRom, Path saveRoot) {
        this(localRom, new PrivateSaveStore(saveRoot), NesCores::create, null);
    }
    /** Client-private opt-in only. The trial key cannot import or replace normal private saves. */
    public FcPrivateEngine(Path localRom, Path saveRoot, LibretroRuntimes.Backend backend) {
        this(localRom, new PrivateSaveStore(saveRoot), factory(backend), null,
                namespacePrefix(backend));
    }
    static String namespacePrefix(LibretroRuntimes.Backend backend) {
        return Objects.requireNonNull(backend)==LibretroRuntimes.Backend.JNI_TRIAL?"jni-trial-v1/":"";
    }
    private static CoreFactory factory(LibretroRuntimes.Backend backend) {
        Objects.requireNonNull(backend);
        if (backend == LibretroRuntimes.Backend.PROCESS) return NesCores::create;
        return variant -> {
            if (!variant.isLibretro()) throw new IllegalStateException("此 FC 核心尚未接入 JNI 试验，请切回独立进程");
            return new cn.piq.fcarcade.core.libretro.GenericLibretroNesCore(variant.isZapper(), backend);
        };
    }
    FcPrivateEngine(Path localRom, PrivateSaveStore store, CoreFactory factory, String testModuleSha) {
        this(localRom,store,factory,testModuleSha,"");
    }
    FcPrivateEngine(Path localRom, PrivateSaveStore store, CoreFactory factory, String testModuleSha, String namespacePrefix) {
        Objects.requireNonNull(localRom); Objects.requireNonNull(store); Objects.requireNonNull(factory);
        if (!namespacePrefix.isEmpty() && !namespacePrefix.equals("jni-trial-v1/")) throw new IllegalArgumentException("Private runtime namespace");
        Path rom = localRom.toAbsolutePath().normalize();
        if (!CAPACITY.tryAcquire()) {
            worker = null; stopping = true; failure = "上一局私人FC仍在运行或保存，请稍后再试";
            stopped.complete(new SaveResult(false, failure)); return;
        }
        worker = new Thread(() -> run(rom, store, factory, testModuleSha, namespacePrefix), "piq-private-fc");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException | Error error) { CAPACITY.release(); throw error; }
    }
    @Override public int maxPlayers() { return 1; }
    @Override public boolean isReady() { return ready && !stopping; }
    @Override public String error() {
        if(failure!=null)return failure;
        var selected=diagnosticCore;String nativeError=selected==null?"":selected.diagnosticError();
        return nativeError.isEmpty()?null:nativeError;
    }
    @Override public void offerInput(int p1, int p2) {
        synchronized (mailbox) {
            if ((p1 & ~4095) != 0 || p2 != 0) {
                inputs[0].failClosed(); inputs[1].failClosed();
                throw new IllegalArgumentException("Private FC accepts one twelve-bit local controller");
            }
            if (!ready || stopping || paused) return;
            inputs[0].offer(nesMask(p1)); inputs[1].offer(nesMask(p2));
        }
    }
    static int nesMask(int libretro) {
        if ((libretro & ~4095) != 0) throw new IllegalArgumentException("Invalid libretro controller mask");
        return (libretro & 0xfc) | ((libretro & 0x100) >>> 8) | ((libretro & 1) << 1);
    }
    @Override public void clearInput() {
        synchronized (mailbox) { inputs[0].failClosed(); inputs[1].failClosed(); }
    }
    @Override public void releasePort(int port) {
        if (port != 0) throw new IllegalArgumentException("Private FC exposes P1 only");
        synchronized (mailbox) { inputs[port].failClosed(); }
    }
    @Override public void paused(boolean value) {
        synchronized (mailbox) {
            if (paused == value) return;
            paused = value; presentationGeneration++;
            inputs[0].failClosed(); inputs[1].failClosed(); frames.clear();
        }
        if (worker != null) LockSupport.unpark(worker);
    }
    @Override public CabinetFrame pollFrame() {
        synchronized (mailbox) { return stopping || paused ? null : frames.pollFirst(); }
    }
    @Override public CompletableFuture<SaveResult> stopAndSave() {
        synchronized (mailbox) {
            stopping = true; ready = false; presentationGeneration++;
            inputs[0].failClosed(); inputs[1].failClosed(); frames.clear();
        }
        if (worker != null) LockSupport.unpark(worker);
        return stopped;
    }
    @Override public void close() { stopAndSave(); }

    private void run(Path rom, PrivateSaveStore store, CoreFactory factory, String testModuleSha, String namespacePrefix) {
        NesCore core = null; SaveResult result = new SaveResult(false, "私人FC尚未加载，未写存档");
        try {
            if (stopping) return;
            byte[] bytes = readLocalRom(rom);
            NesCoreVariant variant = NesCoreVariant.forRom(INesHeader.parse(bytes), false);
            String sha = PrivateSaveStore.sha256(bytes);
            String module = testModuleSha == null ? moduleSha(variant) : testModuleSha;
            if (!module.matches("[0-9a-f]{64}")) throw new IOException("FC core fingerprint is invalid");
            PrivateSaveStore.Key key = new PrivateSaveStore.Key("fc", namespacePrefix + variant.stateNamespace() + "/" + module, sha);
            if (stopping) return;
            core = factory.create(variant);
            diagnosticCore=core;
            if (!core.stateNamespace().equals(variant.stateNamespace())) throw new IOException("FC core namespace differs");
            core.loadRom(bytes);
            Optional<PrivateSaveStore.Snapshot> saved = store.load(key);
            if (saved.isPresent()) {
                byte[] state = saved.get().state();
                if (saved.get().sram().length != 0 || !variant.acceptsPersistentStateHeader(state, sha))
                    throw new IOException("私人FC存档与核心不匹配，原件保留");
                core.loadPersistentState(state);
            }
            core.setControllerState(0, 0); core.setControllerState(1, 0);
            ready = !stopping;
            byte[] rgba = new byte[NesCore.RGBA_BYTES]; float[] samples = new float[4096];
            Audio audio = new Audio(); long next = System.nanoTime();
            while (!stopping) {
                // Consume a queued edge only when an emulated frame is actually due.
                long wait = next - System.nanoTime();
                if (!paused && wait > 0) { LockSupport.parkNanos(wait); continue; }
                int one, two; long generation;
                synchronized (mailbox) {
                    one = paused ? 0 : inputs[0].nextFrame(); two = paused ? 0 : inputs[1].nextFrame();
                    generation = presentationGeneration;
                }
                core.setControllerState(0, one); core.setControllerState(1, two);
                if (paused) { audio.reset(); next = System.nanoTime(); LockSupport.parkNanos(FRAME_NANOS); continue; }
                if (stopping) break;
                long frameStarted = performance.begin();
                core.runFrame();
                performance.completed(frameStarted); completedFrames++;
                core.copyFrameRgba(rgba);
                int count = core.copyAudioSamples(samples);
                int[] abgr = new int[NesCore.WIDTH * NesCore.HEIGHT];
                for (int i = 0; i < abgr.length; i++) {
                    int at = i * 4;
                    abgr[i] = 0xff000000 | (rgba[at] & 255) | ((rgba[at + 1] & 255) << 8) | ((rgba[at + 2] & 255) << 16);
                }
                CabinetFrame picture = new CabinetFrame(NesCore.WIDTH, NesCore.HEIGHT, abgr, 4F / 3F, 0, audio.convert(samples, count));
                synchronized (mailbox) {
                    if (!stopping && !paused && generation == presentationGeneration) {
                        if (frames.size() >= MAX_FRAMES) frames.removeFirst();
                        frames.addLast(picture);
                    }
                }
                next = Math.max(next + FRAME_NANOS, System.nanoTime() - FRAME_NANOS);
            }
            // No frame modulo/command queue: even a paused core freezes and snapshots immediately on its owner.
            core.setControllerState(0, 0); core.setControllerState(1, 0);
            byte[] state = core.savePersistentState();
            if (!variant.acceptsPersistentStateHeader(state, sha)) throw new IOException("FC核心返回了错误身份的存档");
            store.save(key, state, new byte[0]);
            result = new SaveResult(true, "私人FC存档已保存在本机");
        } catch (Exception | LinkageError error) {
            failure = "私人FC启动/运行/保存失败，未报告保存成功：" + safeMessage(error);
            result = new SaveResult(false, failure);
        } finally {
            performance.enabled(false);
            ready = false; stopping = true;
            synchronized (mailbox) { frames.clear(); inputs[0].clear(); inputs[1].clear(); }
            if (core != null) {
                try { core.close(); }
                catch (RuntimeException | LinkageError error) {
                    failure = "私人FC核心关闭异常：" + safeMessage(error);
                    result = new SaveResult(result.saved(), result.message() + "；" + failure);
                }
            }
            CAPACITY.release();
            stopped.complete(result);
        }
    }
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.substring(0, Math.min(240, message.length()));
    }
    private static byte[] readLocalRom(Path rom) throws IOException {
        if (!rom.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nes")
                || !Files.isRegularFile(rom, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("缺少本地.nes ROM；私人模式不会请求下载");
        long size = Files.size(rom);
        if (size < 16 || size > RomRepository.MAX_ROM_BYTES) throw new IOException("私人ROM大小超限");
        try (InputStream stream = Files.newInputStream(rom, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(RomRepository.MAX_ROM_BYTES + 1);
            if (bytes.length != size || stream.read() != -1) throw new IOException("私人ROM读取期间发生变化");
            return bytes;
        }
    }
    private static String moduleSha(NesCoreVariant variant) throws IOException {
        try (InputStream in = FcPrivateEngine.class.getResourceAsStream(NesCores.moduleResource(variant))) {
            if (in == null) throw new IOException("本地FC核心资源缺失");
            byte[] bytes = in.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024) throw new IOException("FC core size");
            return PrivateSaveStore.sha256(bytes);
        }
    }
    /** Owner-local 44.1k mono to stereo PCM48k; no sound device or publisher. */
    private static final class Audio {
        private float previousInput, previousOutput, previousFiltered;
        private long sourceTime = -48000, nextOutput;
        short[] convert(float[] samples, int count) {
            if (count < 0 || count > samples.length) throw new IllegalArgumentException("NES audio bounds");
            short[] output = new short[(int)Math.ceil(count * 48000D / 44100D + 2) * 2]; int size = 0;
            for (int i = 0; i < count; i++) {
                float input = samples[i];
                if (!Float.isFinite(input)) throw new IllegalArgumentException("NES audio value");
                float filtered = input - previousInput + .995F * previousOutput;
                previousInput = input; previousOutput = filtered; sourceTime += 48000;
                while (nextOutput <= sourceTime) {
                    double fraction = sourceTime == 0 ? 1D : (nextOutput - (sourceTime - 48000)) / 48000D;
                    float value = (float)(previousFiltered + (filtered - previousFiltered) * fraction);
                    short scaled = (short)Math.round(Math.max(-1D, Math.min(1D, value * 1.6D)) * 32767D);
                    output[size++] = scaled; output[size++] = scaled; nextOutput += 44100;
                }
                previousFiltered = filtered;
            }
            return Arrays.copyOf(output, size);
        }
        void reset() { previousInput = previousOutput = previousFiltered = 0; sourceTime = -48000; nextOutput = 0; }
    }
}
