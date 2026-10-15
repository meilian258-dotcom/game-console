package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.util.*;
import java.util.concurrent.*;

/** Copies are already immutable on submit; compression and archive writes never run on the game thread. */
final class RecordingSession {
    private static final long MAX_PENDING_BYTES = 48L * 1024 * 1024;
    private record Pending(long sequence, ScreenFrame frame) {}
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(16);
    private final ArrayDeque<Pending> closes = new ArrayDeque<>();
    private final TrackFiles.Writer writer;
    private final ExecutorService worker;
    private final Map<String, ScreenFrame> last = new HashMap<>();
    private volatile boolean accepting = true, closing;
    private long pendingBytes;
    private long sequence;
    private boolean overflowNotified, firstFrameNotified;
    private long submittedFrames, droppedFrames;
    private volatile String failureCode = "none";
    RecordingSession(ReplayFile file) {
        writer = new TrackFiles.Writer(file);
        worker = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task, "PIQ-Replay-Record"); t.setDaemon(true); return t; });
        worker.execute(this::run);
    }
    boolean accepting() { return accepting; }
    synchronized void submit(ScreenFrame frame) {
        if (!accepting) return;
        submittedFrames++;
        long cost = cost(frame);
        if (frame.closed()) {
            // Reserved metadata-only capacity: ordinary pixel backpressure cannot discard a close.
            if (closes.size() >= TrackIndex.MAX_SOURCES) {
                accepting = false; closing = true;
                droppedFrames++; failureCode = "close_overflow";
                ReplayBridge.warn("屏幕关闭事件队列达到安全上限，停止新增屏幕轨；已有画面最多2秒后过期。", null);
                notifyAll(); return;
            }
            closes.addLast(new Pending(sequence++, frame)); pendingBytes += cost; last.remove(frame.source()); notifyAll(); return;
        }
        if (cost > MAX_PENDING_BYTES || pendingBytes + cost > MAX_PENDING_BYTES || !queue.offer(new Pending(sequence++, frame))) {
            droppedFrames++;
            if (!overflowNotified) { overflowNotified = true; ReplayBridge.warn("屏幕录制队列已满，部分画面被跳过；Minecraft录像仍继续。", null); }
            return;
        }
        pendingBytes += cost;
        if (!firstFrameNotified) { firstFrameNotified = true; ReplayBridge.recordingAccepted(this); }
        // Retain only the lightweight close identity, not an extra long-lived pixel copy.
        last.put(frame.source(), new ScreenFrame(frame.source(), frame.dimension(), frame.timestampMillis(), 0, 0, new int[0], List.of()));
        notifyAll();
    }
    synchronized void close(String source, long time) {
        ScreenFrame previous = last.get(source);
        if (previous != null) submit(new ScreenFrame(source, previous.dimension(), time, 0, 0, new int[0], List.of()));
    }
    synchronized void stopAccepting() { accepting = false; closing = true; notifyAll(); }
    void finish() {
        stopAccepting(); worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) { failureCode = "finish_timeout"; ReplayBridge.warn("屏幕轨收尾超时，仅保存已完成的块；原录像继续保存。", null); }
        } catch (InterruptedException interrupted) { failureCode = "finish_interrupted"; Thread.currentThread().interrupt(); ReplayBridge.warn("屏幕轨收尾被中断，仅保存已完成的块。", interrupted); }
        // finish atomically gates late writes on ReplayFile's monitor, before Replay saves/closes it.
        try {
            long submitted, dropped;
            synchronized (this) { submitted = submittedFrames; dropped = droppedFrames; }
            String reason = failureCode;
            var summary = writer.finish(submitted, dropped, reason);
            ReplayBridge.recordingFinished(summary, submitted, dropped, reason);
        } catch (Exception failure) { ReplayBridge.warn("屏幕轨索引或诊断保存失败；原Minecraft录像不受阻止。", failure); }
    }
    private void run() {
        var batch = new ArrayList<ScreenFrame>(8);
        try {
            while (!closing || !empty()) {
                ScreenFrame frame = next();
                if (frame != null) batch.add(frame);
                if (batch.size() == 8 || closing && empty() && !batch.isEmpty()) {
                    writer.append(batch);
                    synchronized (this) { for (ScreenFrame done : batch) pendingBytes -= cost(done); }
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) writer.append(batch);
        } catch (Exception | LinkageError failure) {
            accepting = false; closing = true;
            failureCode = "write_failed";
            ReplayBridge.warn("屏幕录制达到配额或写入失败，已停止补充画面；原Minecraft录像仍继续。", failure);
        } finally { synchronized (this) { queue.clear(); closes.clear(); pendingBytes = 0; last.clear(); } }
    }
    private synchronized boolean empty() { return queue.isEmpty() && closes.isEmpty(); }
    private synchronized ScreenFrame next() throws InterruptedException {
        if (empty() && !closing) wait(100);
        Pending video = queue.peek(), close = closes.peekFirst();
        if (video == null && close == null) return null;
        if (video == null || close != null && (close.frame.timestampMillis() < video.frame.timestampMillis()
                || close.frame.timestampMillis() == video.frame.timestampMillis() && close.sequence < video.sequence)) return closes.removeFirst().frame;
        return queue.remove().frame;
    }
    private static long cost(ScreenFrame frame) { return (long) frame.width() * frame.height() * 4 + frame.vertices().size() * 40L + 1024; }
}
