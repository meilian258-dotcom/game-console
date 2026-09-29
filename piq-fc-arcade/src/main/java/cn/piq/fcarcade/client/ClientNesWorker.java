package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.score.RoadRaceScoreRule;
import cn.piq.fcarcade.session.FrameDigest;
import cn.piq.fcarcade.session.LockstepDigestTracker;
import cn.piq.fcarcade.session.ControllerInputTransitions;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** No Minecraft, network, sound-line or GL calls are permitted in this class. */
final class ClientNesWorker implements AutoCloseable {
    static final int MAX_SESSIONS = 16;
    static final int MAX_INPUTS = 4096;
    static final int MAX_COMMANDS = 32;
    static final int MAX_EVENTS = 64;
    static final int MAX_AUDIO = 12;
    private static final long FRAME_NANOS = 1_000_000_000L / 60L;
    private static final long SLICE_NANOS = 4_000_000L;
    private static final Scheduler SCHEDULER = new Scheduler();

    @FunctionalInterface interface Factory { NesCore create() throws Exception; }
    /** Core-thread tap: implementations only copy into bounded media mailboxes. */
    interface MediaTap {
        boolean enabled();
        void frame(byte[] rgba, float[] mono, int count);
        default void failed(Throwable failure) {}
    }
    private volatile MediaTap mediaTap;
    void mediaTap(MediaTap tap) { mediaTap = tap; }
    @FunctionalInterface private interface Command { void run() throws Exception; }
    record Input(long target, int one, int two,int zapperState) {
        Input(long target,int one,int two){this(target,one,two,cn.piq.fcarcade.session.ZapperInput.NEUTRAL);}
        Input {cn.piq.fcarcade.session.ZapperInput.validate(zapperState);}
    }
    record Event(String kind, long frame, long number, String message, byte[] bytes) {
        Event { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    record Picture(long generation, long frame, long silentUntil, byte[] rgba) {
        Picture { rgba = rgba.clone(); }
        @Override public byte[] rgba() { return rgba.clone(); }
    }
    record Audio(long generation, float[] samples) {
        Audio { samples = samples.clone(); }
        @Override public float[] samples() { return samples.clone(); }
    }
    record Delivery(long generation, Picture picture, List<Audio> audio, List<Event> events) { }
    private record TaggedCommand(long generation, Command command) { }

    // Mailbox fields: protected by this; the owner never holds this lock while
    // invoking a core, reading ROMs or writing a calibration report.
    private final ArrayDeque<TaggedCommand> commands = new ArrayDeque<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final ArrayDeque<Audio> audio = new ArrayDeque<>();
    private Picture picture;
    private volatile long generation = 1;
    private volatile boolean closed;
    private volatile boolean controller;
    private volatile boolean paused;
    private volatile boolean audible;
    private volatile boolean ready;
    private volatile long publishedFrame;
    private volatile int publishedInputs;
    private volatile long publishedTarget;
    private final cn.piq.fcarcade.client.performance.FramePerformance performance = new cn.piq.fcarcade.client.performance.FramePerformance();
    private volatile boolean failed;
    private volatile boolean controllerPresentationEnabled = true;
    private volatile int appliedZapper=cn.piq.fcarcade.session.ZapperInput.NEUTRAL;
    boolean appliedZapperTrigger(){return isReady()&&!paused&&cn.piq.fcarcade.session.ZapperInput.trigger(appliedZapper);}
    private final ControllerInputTransitions[] streamInputs = {
            new ControllerInputTransitions(), new ControllerInputTransitions()};
    private final ControllerFramePresentation controllerPresentation = new ControllerFramePresentation();

    // Everything below is exclusively accessed on SCHEDULER.thread.
    private final Factory factory;
    private final boolean lockstep;
    private final String romSha;
    private NesCore core;
    private long ownerGeneration = 1;
    private long frame;
    private long silentUntil;
    private long nextFrame;
    private volatile boolean awaitingSnapshot;
    private boolean snapshotRequested;
    private final ArrayDeque<Input> inputs = new ArrayDeque<>();
    private final LockstepFramePacer pacer = new LockstepFramePacer(FRAME_NANOS);
    private int dueFrames;
    private RoadRaceScoreTracker scoreTracker;
    private ScoreCalibrationSession calibration;
    private final byte[] pixels = new byte[NesCore.RGBA_BYTES];
    private final float[] samples = new float[4096];

    ClientNesWorker(Factory factory, boolean lockstep, boolean controller,
                    String romSha, boolean awaitingSnapshot) {
        this.factory = factory;
        this.lockstep = lockstep;
        this.controller = controller;
        this.romSha = romSha;
        this.awaitingSnapshot = awaitingSnapshot;
        if (!SCHEDULER.add(this)) {
            closed = true;
            throw new IllegalStateException("FC 后台会话数量超过上限 " + MAX_SESSIONS);
        }
    }

    void configure(boolean controller, boolean paused, boolean audible) {
        this.controller = controller;
        this.paused = paused;
        this.audible = audible;
        if (paused) {
            clearInput(0);
            clearInput(1);
        }
        SCHEDULER.wake();
    }

    void input(int player, int mask) {
        synchronized (this) {
            if (player < 0 || player > 1 || closed || failed || paused) return;
            if (!streamInputs[player].offer(mask)) controllerPresentation.clear();
        }
        SCHEDULER.wake();
    }
    void clearInput(int player) {
        synchronized (this) {
            if (player >= 0 && player < 2) streamInputs[player].clear();
            controllerPresentation.clear();
        }
    }
    void presentControllerFrame() { controllerPresentation.present(); }
    void setControllerPresentationEnabled(boolean enabled) {
        synchronized (this) {
            if (controllerPresentationEnabled != enabled) controllerPresentation.clear();
            controllerPresentationEnabled = enabled;
        }
    }
    int presentedControllerMask(int port) {
        return !isReady() || paused ? 0 : controllerPresentation.presented(port);
    }
    int appliedControllerMask(int port) {
        return !isReady() || paused ? 0 : controllerPresentation.latest(port);
    }

    boolean enqueue(Input input) {
        return offer(() -> {
            if (awaitingSnapshot || input.target <= frame) return;
            Input last = inputs.peekLast();
            if (last != null && input.target <= last.target) return;
            if (inputs.size() >= MAX_INPUTS) { requireResync(); return; }
            inputs.addLast(input);
            publishedTarget = input.target;
        });
    }

    boolean history(long start, List<Input> history) {
        if (history.size() > MAX_INPUTS) return false;
        List<Input> copy = List.copyOf(history);
        return offer(() -> {
            if (frame != start || awaitingSnapshot) return;
            inputs.clear();
            controllerPresentation.clear();
            inputs.addAll(copy);
            silentUntil = inputs.isEmpty() ? frame : inputs.peekLast().target;
            publishedTarget = silentUntil;
            pacer.reset();
            dueFrames = 0;
            publishPicture();
        });
    }

    void reset() {
        replace(() -> {
            closeCore();
            createCore();
            frame = 0;
            silentUntil = 0;
            awaitingSnapshot = false;
            publishPicture();
        });
    }

    void snapshot(long atFrame, byte[] state, boolean persistent) {
        byte[] copy = state.clone();
        replace(() -> {
            // An already accepted authoritative initial save must win over a
            // frame that was in flight when replace advanced the generation.
            // Never silently skip it because that obsolete frame just finished.
            if (persistent) core.loadPersistentState(copy);
            else core.loadTransientState(copy);
            scoreTracker = RoadRaceScoreRule.ROM_SHA256.equalsIgnoreCase(romSha)
                    ? new RoadRaceScoreTracker() : null;
            frame = atFrame;
            silentUntil = persistent ? atFrame : atFrame + 3;
            awaitingSnapshot = false;
            emit(new Event(persistent ? "loaded" : "sync", frame, 0, "", null));
            publishPicture();
        });
    }

    boolean requestSnapshot() {
        // Repeated requests occupy one command, and one flag on the owner.
        synchronized (this) {
            if (closed || failed) return false;
            if (commands.stream().anyMatch(c -> c.command instanceof SnapshotCommand)) return true;
            if (commands.size() >= MAX_COMMANDS) return false;
            commands.addLast(new TaggedCommand(generation, new SnapshotCommand()));
        }
        SCHEDULER.wake();
        return true;
    }
    private final class SnapshotCommand implements Command {
        @Override public void run() { snapshotRequested = true; }
    }

    boolean calibrationStart() {
        return offer(() -> calibration = new ScoreCalibrationSession());
    }
    boolean calibrationMark(int score) {
        return offer(() -> {
            if (calibration == null) return;
            if (calibration.sampleCount() >= 128) {
                notice("校准样本已达 128 个，请先生成报告");
                return;
            }
            int count = calibration.capture(core, score);
            notice("已记录第 " + count + " 个样本，画面分数=" + score);
        });
    }
    boolean calibrationFinish(Path gameDirectory) {
        return offer(() -> {
            if (calibration == null) return;
            try {
                Path reports = cn.piq.fcarcade.storage.FcStoragePaths.prepare(gameDirectory,
                        cn.piq.fcarcade.storage.FcStoragePaths.Area.CALIBRATION);
                var result = calibration.finish(reports, romSha);
                calibration = null;
                notice("校准报告已生成：" + result.reportPath() + "（样本 "
                        + result.sampleCount() + "，精确候选 " + result.candidateCount() + "）");
            } catch (Exception error) { notice("暂时不能生成报告：" + error.getMessage()); }
        });
    }
    boolean calibrationCancel() { return offer(() -> calibration = null); }

    synchronized Delivery drain() {
        Delivery result = new Delivery(generation, picture, List.copyOf(audio), List.copyOf(events));
        picture = null;
        audio.clear();
        events.clear();
        return result;
    }
    long generation() { return generation; }
    void performanceEnabled(boolean enabled) { performance.enabled(enabled); }
    record Performance(cn.piq.fcarcade.client.performance.FramePerformance.Sample sample, String state,
                       long frame, long pendingFrames, int audioBuffers, double audioMs) {}
    synchronized Performance performance() {
        long sampleCount = 0;
        for (Audio packet : audio) sampleCount += packet.samples.length;
        return new Performance(performance.sample(), closed ? "已关闭" : failed ? "运行失败" : !ready ? "加载中"
                : paused ? "已暂停" : awaitingSnapshot ? "等待同步快照" : "运行中", publishedFrame,
                lockstep ? Math.max(0, publishedTarget - publishedFrame) : -1, audio.size(), sampleCount * 1000D / 44100D);
    }
    boolean isReady() { return ready && !closed && !failed; }
    String diagnostic() {
        return (closed ? "closed" : failed ? "failed" : ready ? "running" : "loading")
                + " frame=" + publishedFrame + " inputs=" + publishedInputs;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            performance.enabled(false);
            generation++;
            commands.clear();
            appliedZapper=cn.piq.fcarcade.session.ZapperInput.NEUTRAL;
            events.clear();
            audio.clear();
            picture = null;
            streamInputs[0].clear();
            streamInputs[1].clear();
            controllerPresentation.clear();
        }
        SCHEDULER.wake();
    }

    private synchronized boolean offer(Command command) {
        if (closed || failed || commands.size() >= MAX_COMMANDS) return false;
        commands.addLast(new TaggedCommand(generation, command));
        SCHEDULER.wake();
        return true;
    }

    private void replace(Command command) {
        synchronized (this) {
            if (closed || failed) return;
            generation++;
            appliedZapper=cn.piq.fcarcade.session.ZapperInput.NEUTRAL;
            commands.clear();
            events.clear();
            audio.clear();
            picture = null;
            // Clear before returning to the input sampler, not later on the
            // owner: a fresh held-key update after reset must survive it.
            streamInputs[0].clear();
            streamInputs[1].clear();
            controllerPresentation.clear();
            commands.addFirst(new TaggedCommand(generation, () -> {
                performance.reset();
                publishedTarget = 0;
                inputs.clear();
                calibration = null;
                snapshotRequested = false;
                pacer.reset();
                dueFrames = 0;
                command.run();
            }));
        }
        SCHEDULER.wake();
    }

    /** Returns whether this session did work. It never runs on a caller thread. */
    private boolean slice() {
        if (closed) { closeCore(); return false; }
        if (failed) { closeCore(); return false; }
        try {
            if (core == null) {
                try {
                    createCore();
                } catch (Throwable error) {
                    // Queued snapshot/reset may advance generation while the
                    // factory is loading. They all depend on this initialization,
                    // so a failure is terminal for the CURRENT mailbox, not a
                    // stale frame error to discard and retry without a bound.
                    if (!closed) {
                        ownerGeneration = generation;
                        System.getLogger(ClientNesWorker.class.getName()).log(System.Logger.Level.ERROR,"FC core initialization failed",error);
                        emit(new Event("error", frame, 0, error.toString(), null));
                        failed = true;
                    }
                    closeCore();
                    return false;
                }
                if (closed) { closeCore(); return false; }
                emit(new Event("ready", 0, 0, "", null));
                publishPicture();
                return true;
            }
            TaggedCommand command;
            synchronized (this) { command = commands.pollFirst(); }
            if (command != null) {
                if (command.generation == generation) {
                    ownerGeneration = command.generation;
                    command.command.run();
                }
                return true;
            }
            if (ownerGeneration != generation) return false;
            if (snapshotRequested && frame % 3 == 0 && !awaitingSnapshot) {
                snapshotRequested = false;
                emit(new Event("snapshot", frame, 0, "", core.savePersistentState()));
                return true;
            }
            if (paused || awaitingSnapshot) { pacer.reset(); dueFrames = 0; nextFrame = 0; return false; }
            long now = System.nanoTime();
            boolean catchup = lockstep && frame < silentUntil;
            if (lockstep && !catchup && dueFrames == 0) {
                Input last = inputs.peekLast();
                dueFrames = pacer.framesDue(now, last == null ? 0 : last.target - frame);
            }
            long deadline = now + SLICE_NANOS;
            int advanced = 0;
            while (advanced < 3 && System.nanoTime() < deadline && !closed
                    && ownerGeneration == generation && !paused) {
                if (snapshotRequested && frame % 3 == 0) break;
                int frameOne,frameTwo,gun=cn.piq.fcarcade.session.ZapperInput.NEUTRAL;
                long presentationRevision;
                synchronized (this) {
                    if (ownerGeneration != generation || paused || closed) break;
                    presentationRevision = controllerPresentation.revision();
                    if (lockstep) {
                        Input step = inputs.peekFirst();
                        if (step == null || (!catchup && dueFrames <= 0)) break;
                        frameOne = step.one; frameTwo = step.two;
                        gun=step.zapperState;
                    } else {
                        if (System.nanoTime() < nextFrame) break;
                        frameOne = streamInputs[0].nextFrame(); frameTwo = streamInputs[1].nextFrame();
                    }
                }
                core.setControllerState(0,frameOne);
                core.setControllerState(1,frameTwo);
                if(core.supportsZapper())core.setZapperState(cn.piq.fcarcade.session.ZapperInput.x(gun),cn.piq.fcarcade.session.ZapperInput.y(gun),
                        cn.piq.fcarcade.session.ZapperInput.offscreen(gun),cn.piq.fcarcade.session.ZapperInput.trigger(gun));
                else if(gun!=cn.piq.fcarcade.session.ZapperInput.NEUTRAL)throw new IllegalStateException("Gun frame supplied to legacy core");
                long frameStarted = performance.begin();
                core.runFrame();
                performance.completed(frameStarted);
                synchronized (this) {
                    if(ownerGeneration==generation&&!closed&&!paused)appliedZapper=gun;
                    if (ownerGeneration == generation && !paused && !closed && controllerPresentationEnabled && !catchup)
                        controllerPresentation.complete(presentationRevision, frameOne, frameTwo);
                }
                frame++;
                advanced++;
                if (!catchup) dueFrames = Math.max(0, dueFrames - 1);
                observeScore();
                int count = core.copyAudioSamples(samples);
                if (audible && !catchup && count > 0) publishAudio(Arrays.copyOf(samples, count));
                MediaTap tap = mediaTap;
                if (!catchup && !closed && ownerGeneration == generation && tap != null) {
                    try {
                        if(tap.enabled()){
                            core.copyFrameRgba(pixels);
                            // Host-local mute/distance must not mute remote players.
                            // No compression or socket writes on this core thread.
                            tap.frame(pixels, samples, count);
                        }
                    } catch(RuntimeException|LinkageError failure) {
                        if(mediaTap==tap)mediaTap=null;
                        try{tap.failed(failure);}catch(RuntimeException|LinkageError ignored){}
                        notice("FC音画采集失败，发布已停用。");
                    }
                }
                if (lockstep) {
                    if (frame >= inputs.peekFirst().target) inputs.removeFirst();
                    if (frame >= silentUntil && frame % LockstepDigestTracker.INTERVAL_FRAMES == 0) {
                        core.copyFrameRgba(pixels);
                        emit(new Event("digest", frame, FrameDigest.calculate(pixels), "", null));
                    }
                } else {
                    nextFrame = nextFrame == 0 ? now + FRAME_NANOS : nextFrame + FRAME_NANOS;
                    if (now - nextFrame > FRAME_NANOS * 3) nextFrame = now + FRAME_NANOS;
                }
            }
            publishedFrame = frame;
            publishedInputs = inputs.size();
            if (advanced > 0) {
                if (catchup && frame >= silentUntil) {
                    pacer.reset();
                    dueFrames = 0;
                    emit(new Event("synced", frame, 0, "", null));
                }
                publishPicture();
            }
            return advanced > 0;
        } catch (Throwable error) {
            if (ownerGeneration == generation && !closed) {
                System.getLogger(ClientNesWorker.class.getName()).log(System.Logger.Level.ERROR,"FC core worker stopped",error);
                emit(new Event("error", frame, 0, error.toString(), null));
                failed = true;
            }
            closeCore();
            return false;
        }
    }

    private void createCore() throws Exception {
        core = factory.create();
        scoreTracker = RoadRaceScoreRule.ROM_SHA256.equalsIgnoreCase(romSha)
                ? new RoadRaceScoreTracker() : null;
        ready = true;
    }
    private void closeCore() {
        controllerPresentation.clear();
        NesCore previous = core;
        core = null;
        ready = false;
        if (previous != null) {
            try { previous.close(); } catch (Throwable ignored) { }
        }
        inputs.clear();
    }
    private void requireResync() {
        controllerPresentation.clear();
        inputs.clear();
        awaitingSnapshot = true;
        publishedTarget = publishedFrame;
        performance.reset();
        emit(new Event("resync", frame, 0, "", null));
    }
    private void observeScore() {
        if (scoreTracker == null) return;
        scoreTracker.observe(core).ifPresent(score -> emit(new Event("score", frame, score, "", null)));
        if (scoreTracker.consumeRoundEnded()) emit(new Event("score", frame, 0, "", null));
    }
    private void notice(String text) { emit(new Event("notice", frame, 0, text, null)); }
    private synchronized void emit(Event event) {
        if (closed || ownerGeneration != generation) return;
        if (events.size() >= MAX_EVENTS) {
            // Do not silently lose score/save events: force a visible fail-safe.
            events.clear();
            events.add(new Event("error", frame, 0, "FC 后台结果队列已满", null));
            failed = true;
            return;
        }
        events.addLast(event);
    }
    private void publishPicture() {
        publishedFrame = frame;
        core.copyFrameRgba(pixels);
        Picture next = new Picture(ownerGeneration, frame, awaitingSnapshot ? Math.max(frame + 3, silentUntil) : silentUntil, pixels);
        synchronized (this) { if (!closed && ownerGeneration == generation) picture = next; }
    }
    private synchronized void publishAudio(float[] next) {
        if (closed || ownerGeneration != generation) return;
        if (audio.size() == MAX_AUDIO) audio.removeFirst();
        audio.addLast(new Audio(ownerGeneration, next));
    }

    /** Globally bounded, one owner thread, 3:1 controller/observer scheduling. */
    private static final class Scheduler implements Runnable {
        private final List<ClientNesWorker> workers = new ArrayList<>();
        private final Thread thread;
        private int cursor;
        private int turn;
        Scheduler() {
            thread = new Thread(this, "PIQ-FC-core-owner");
            thread.setDaemon(true);
            thread.start();
        }
        synchronized boolean add(ClientNesWorker worker) {
            if (workers.size() >= MAX_SESSIONS) return false;
            workers.add(worker);
            wake();
            return true;
        }
        void wake() { LockSupport.unpark(thread); }
        private synchronized ClientNesWorker select() {
            if (workers.isEmpty()) return null;
            boolean high = (turn++ & 3) != 3;
            for (int pass = 0; pass < 2; pass++) {
                for (int scanned = 0; scanned < workers.size(); scanned++) {
                    cursor = (cursor + 1) % workers.size();
                    ClientNesWorker worker = workers.get(cursor);
                    if (worker.closed || pass == 1 || worker.controller == high) return worker;
                }
            }
            return null;
        }
        @Override public void run() {
            int idle = 0;
            for (;;) {
                ClientNesWorker worker = select();
                boolean worked = worker != null && worker.slice();
                if (worker != null && worker.closed) {
                    // close() can arrive during a native call after slice's
                    // entry check. Retire only after owner-thread destruction.
                    worker.closeCore();
                    synchronized (this) { workers.remove(worker); }
                }
                if (worked) idle = 0;
                else if (++idle >= MAX_SESSIONS) {
                    idle = 0;
                    LockSupport.parkNanos(1_000_000L);
                }
            }
        }
    }
}
