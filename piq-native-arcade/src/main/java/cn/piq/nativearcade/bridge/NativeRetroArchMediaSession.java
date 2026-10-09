// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.libretro.LibretroContentFiles;
import cn.piq.retro.libretro.LibretroProfile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit client MEDIA adapter. RetroArch owns timing; Minecraft keeps seats and AV distribution. */
public final class NativeRetroArchMediaSession implements CabinetEmulator {
    private final NativeJniInputBuffer inputs = new NativeJniInputBuffer();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final RetroArchSession session;
    private final String contentName;
    private volatile String failure;
    private volatile NativeMameLoadFailure.Report loadFailure;
    private CabinetFrame image;

    public static RetroArchSession.Config config(String contentName) {
        if (contentName == null || !contentName.matches("[a-z0-9_]{1,32}\\.zip"))
            throw new IllegalArgumentException("街机 ZIP 必须保留驱动原名");
        var mame = NativeJniMediaSession.profile();
        var core = mame.cores().get("windows-x64");
        var profile = new NetplayProfile(NativeRetroArchMediaSession.class, core.resource(), core.sha256(),
                contentName, mame.options(), 1, 48000, LibretroContentFiles.MAX_MAIN, 4);
        var runtime = new LibretroProfile.Artifact("/core/libretro-jni/windows-x64/libc++.dll",
                "7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4", 1659392);
        return new RetroArchSession.Config(profile, core,
                List.of(new RetroArchSession.Dependency(NetplayProcess.class, runtime, "libc++.dll", 1659392)));
    }

    public NativeRetroArchMediaSession(Path rom) throws IOException {
        String reason = unavailableReason();
        if (reason != null) throw new IOException(reason);
        Path source = rom.toAbsolutePath().normalize();
        String name = source.getFileName().toString();
        contentName = name;
        session = new RetroArchSession(config(name), () -> inspect(source, name));
        session.inputProvider(() -> {
            int[] pads = inputs.nextFrame();
            if (closing.get()) return new int[4];
            for (int p = 0; p < pads.length; p++) pads[p] = NativeArcadeButtons.toMame(pads[p]);
            return pads;
        });
        session.start();
    }

    private LibretroContentFiles inspect(Path source, String name) throws IOException {
        var files = new TreeMap<String, Path>();
        files.put(name, source);
        for (String bios : NativeRomStaging.AUXILIARY_NAMES) {
            Path path = source.getParent().resolve(bios);
            if (!bios.equals(name) && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) files.put(bios, path);
        }
        return LibretroContentFiles.inspect(name, files, LibretroContentFiles.MAX_MAIN, 16 * 1024 * 1024,
                () -> { if (closing.get()) throw new IOException("街机开局已取消"); });
    }

    public static String unavailableReason() {
        return NetplayProcess.unavailableReason() == null ? null : "RetroArch 本机运行目前仅支持 Windows x64";
    }
    @Override public int maxPlayers() { return 4; }
    @Override public boolean isReady() { return !closing.get() && failure == null && session.ready(); }
    @Override public String error() {
        if (failure != null) return failure;
        String reason = session.error();
        if (reason == null) return null;
        var report = loadFailure;
        if (report == null) loadFailure = report = NativeMameLoadFailure.interpret(contentName, session.diagnostic());
        return report == null ? reason : report.summary();
    }
    /** Captured locally before the cabinet retires this adapter; never sent to other players. */
    @Override public String failureDiagnostic() {
        String reason = error();
        var report = loadFailure;
        return (report == null ? "RetroArch 本机运行／玩家音画串流：" + (reason == null ? "尚无失败" : reason)
                : report.detail()) + "\n\n原生诊断（仅本机；分享前检查私人路径）：\n" + session.diagnostic();
    }
    public CompletableFuture<Void> terminated() { return session.terminated(); }
    @Override public void offerInput(int a, int b) { offerInputs(a, b, 0, 0); }
    @Override public void offerInputs(int a, int b, int c, int d) {
        if (!closing.get() && !inputs.offer(a, b, c, d)) {
            failure = "街机输入队列已满";
            close();
        }
    }
    @Override public void releasePort(int port) { inputs.releasePort(port); }
    @Override public boolean supportsCoinPreservingRelease() { return true; }
    @Override public void releaseGameplayPortKeepingCoin(int port) { inputs.releaseGameplayPortKeepingCoin(port); }
    @Override public void clearInput() { inputs.clear(); }

    @Override public List<String> diagnostics() {
        var snapshot = inputs.snapshot();
        var ports = snapshot.ports();
        return List.of("MAME · RetroArch 独立进程（本机运行，音画串流）",
                session.status(),
                String.format(Locale.ROOT, "待输入 1P:%d 2P:%d 3P:%d 4P:%d · 无冲突合并 %d",
                        ports.get(0).pending(), ports.get(1).pending(), ports.get(2).pending(), ports.get(3).pending(), snapshot.mergedStates()),
                "本入口不启用 RetroArch Netplay，不读写旧 JNI 存档");
    }

    /** Drain a bounded native queue, keeping audio for every consumed frame and only the newest image. */
    @Override public synchronized CabinetFrame pollFrame() {
        if (closing.get()) return null;
        var chunks = new ArrayList<short[]>();
        int count = 0;
        boolean received = false;
        for (int n = 0; n < 6; n++) {
            var frame = session.poll();
            if (frame == null) break;
            received = true;
            if (frame.sampleRate() != 48000) {
                failure = "RetroArch 街机输出采样率不匹配";
                close();
                return null;
            }
            chunks.add(frame.stereo()); count += frame.stereo().length;
            if (frame.rgba().length > 0) image = picture(frame);
        }
        if (!received || image == null) return null;
        short[] audio = new short[Math.min(count, 32768)];
        int skip = count - audio.length, offset = 0;
        for (short[] chunk : chunks) {
            int from = Math.min(skip, chunk.length); skip -= from;
            int length = chunk.length - from;
            System.arraycopy(chunk, from, audio, offset, length); offset += length;
        }
        return new CabinetFrame(image.width(), image.height(), image.abgr(), image.displayAspect(), image.rotation(), audio);
    }

    static CabinetFrame picture(NetplayProcess.Frame frame) {
        int[] pixels = new int[frame.rgba().length / 4];
        ByteBuffer.wrap(frame.rgba()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        // PNP7 already converts presented DAR to raw DAR; NetplayProcess converts CCW to CW.
        // Unlike the direct JNI adapter, no second aspect inversion belongs here.
        return new CabinetFrame(frame.width(), frame.height(), pixels, frame.aspect(), frame.rotation(), new short[0]);
    }

    @Override public synchronized void close() {
        if (!closing.compareAndSet(false, true)) return;
        inputs.clear(); image = null;
        session.close(); // asynchronous; terminated() is the actual process/workspace completion
    }
}
