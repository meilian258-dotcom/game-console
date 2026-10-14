// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.client.performance.FramePerformance;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Reuses the existing pure-Java frame meter; no Minecraft or native calls are made here. */
final class NativeMediaDiagnostics {
    private final NativeJniInputBuffer inputs;
    private final FramePerformance frames;
    private final LongSupplier clock;
    private volatile double targetFps;
    private volatile long lastCompleted;
    private volatile boolean completed;

    NativeMediaDiagnostics(NativeJniInputBuffer inputs) { this(inputs, System::nanoTime); }
    NativeMediaDiagnostics(NativeJniInputBuffer inputs, LongSupplier clock) {
        this.inputs = inputs;
        this.clock = clock;
        frames = new FramePerformance(clock);
    }
    void loaded(double fps) { targetFps = fps; frames.enabled(true); }
    long beginFrame() { return frames.begin(); }
    void completedFrame(long started) {
        frames.completed(started);
        lastCompleted = clock.getAsLong();
        completed = true;
    }
    List<String> lines() {
        var sample = frames.sample();
        var queue = inputs.snapshot();
        var ports = queue.ports();
        long oldest = 0, longest = 0;
        int peak = 0;
        for (var port : ports) {
            oldest = Math.max(oldest, port.oldestWaitMillis());
            longest = Math.max(longest, port.maximumWaitMillis());
            peak = Math.max(peak, port.peakPending());
        }
        String speed = sample.ready() ? String.format(Locale.ROOT, "MAME JNI：实际 %.1f / 目标 %.1f 帧/秒", sample.fps(), targetFps)
                : "MAME JNI：" + (sample.enabled() ? "速度采样中（约 1 秒）" : "正在准备核心");
        String age = completed ? Math.max(0, clock.getAsLong() - lastCompleted) / 1_000_000 + " ms" : "尚无完成帧";
        return List.of(speed,
                String.format(Locale.ROOT, "核心调用均 %.1f / 最大 %.1f ms · 距今 %s", sample.averageMs(), sample.maximumMs(), age),
                String.format(Locale.ROOT, "待输入 1P:%d 2P:%d 3P:%d 4P:%d · 最老 %d ms",
                        ports.get(0).pending(), ports.get(1).pending(), ports.get(2).pending(), ports.get(3).pending(), oldest),
                String.format(Locale.ROOT, "本局峰队列 %d / 最长 %d ms · 无冲突合并 %d", peak, longest, queue.mergedStates()));
    }
}
