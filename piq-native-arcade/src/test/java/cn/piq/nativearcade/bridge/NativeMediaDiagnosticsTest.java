package cn.piq.nativearcade.bridge;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMediaDiagnosticsTest {
    @Test void preparationAndWarmupDoNotInventCoreSpeed() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        var d = new NativeMediaDiagnostics(q, clock::get);
        assertTrue(d.lines().getFirst().contains("正在准备核心"));
        assertTrue(d.lines().get(1).contains("尚无完成帧"));
        d.loaded(60); assertTrue(d.lines().getFirst().contains("采样中"));
    }
    @Test void recentCoreCostAndFpsAreNotNominalSpeedOrLifetimeAverage() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        var d = new NativeMediaDiagnostics(q, clock::get); d.loaded(60);
        for (int bucket = 1; bucket <= 10; bucket++) {
            clock.set(bucket * 100_000_000L); long start = d.beginFrame();
            clock.addAndGet(20_000_000L); d.completedFrame(start);
        }
        clock.set(1_100_000_000L); var lines = d.lines();
        assertTrue(lines.get(0).contains("实际 10.0 / 目标 60.0"));
        assertTrue(lines.get(1).contains("均 20.0 / 最大 20.0 ms"));
        assertTrue(lines.get(1).contains("距今 80 ms"));
        clock.set(5_000_000_000L); assertTrue(d.lines().get(0).contains("实际 0.0"));
        assertTrue(d.lines().get(1).contains("距今 3980 ms"));
    }
    @Test void repeatedDiagnosticReadsDoNotConsumeInputsOrRecordFrames() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        var d = new NativeMediaDiagnostics(q, clock::get); d.loaded(60);
        assertTrue(q.offer(1, 0, 0, 0)); assertTrue(q.offer(0, 0, 0, 0));
        clock.set(2_000_000_000L); var lines = d.lines(); var input = q.snapshot();
        for (int n = 0; n < 100; n++) { assertEquals(lines, d.lines()); assertEquals(input, q.snapshot()); }
        assertTrue(lines.get(0).contains("实际 0.0")); assertTrue(lines.get(2).contains("1P:2"));
        assertEquals(1, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
    }
    @Test void focusResetDoesNotEraseDiagnosticEvidence() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        var d = new NativeMediaDiagnostics(q, clock::get);
        q.offer(1, 0, 0, 0); q.offer(0, 0, 0, 0); clock.set(6_400_000_000L); q.clear();
        assertTrue(d.lines().get(2).contains("1P:0"));
        assertTrue(d.lines().get(3).contains("峰队列 2 / 最长 6400 ms"));
    }
}
