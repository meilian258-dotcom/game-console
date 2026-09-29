// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.netplay;

import java.util.*;

/**
 * Owner-thread, bounded two-pad prediction history. This class grants no input authority:
 * the transport must authenticate the room, peer, frame and lane before correcting it.
 * Snapshots are BEFORE the numbered frame. Replay never publishes audio/video or polls input.
 */
public final class RollbackTimeline<V> {
    public static final int HISTORY = 32;
    public static final int MAX_STATE = 2 * 1024 * 1024;
    public interface Core<V> {
        byte[] save();
        void restore(byte[] state);
        V step(int p1, int p2, boolean present);
    }
    public record Input(long frame, int p1, int p2, int known) {
        public Input {
            if (frame < 0 || (p1 & ~65535) != 0 || (p2 & ~65535) != 0 || (known & ~3) != 0)
                throw new IllegalArgumentException("Rollback input bounds");
        }
        public int pad(int lane) { checkLane(lane); return lane == 0 ? p1 : p2; }
    }
    private static final class Entry {
        Input input;
        byte[] before;
        Entry(Input input, byte[] before) { this.input = input; this.before = before; }
    }
    private final Core<V> core;
    private final Thread owner = Thread.currentThread();
    private final NavigableMap<Long, Entry> history = new TreeMap<>();
    private long next, oldest, replays;
    private int stateSize;

    public RollbackTimeline(Core<V> core, long nextFrame) {
        this.core = Objects.requireNonNull(core);
        if (nextFrame < 0 || nextFrame > Long.MAX_VALUE - HISTORY) throw new IllegalArgumentException("Frame");
        next = oldest = nextFrame;
    }
    public long next() { check(); return next; }
    public long oldest() { check(); return oldest; }
    public long replayedFrames() { check(); return replays; }
    public int retained() { check(); return history.size(); }
    public boolean canAdvance() { check(); return history.size() < HISTORY && next < Long.MAX_VALUE - HISTORY; }
    public V advance(int p1, int p2, int known) {
        check();
        if (!canAdvance()) throw new IllegalStateException("Rollback history exhausted; wait for confirmation");
        var input = new Input(next, p1, p2, known);
        var entry = new Entry(input, snapshot());
        V output = core.step(p1, p2, true);
        history.put(next++, entry);
        return output;
    }
    public Input input(long frame) { check(); return entry(frame).input; }
    public List<Input> inputs(long first, long end) {
        check();
        if (first < oldest || end < first || end > next) throw new IllegalArgumentException("History range");
        return history.subMap(first, true, end, false).values().stream().map(e -> e.input).toList();
    }
    /**
     * Accept a previously missing input for one authenticated lane. Duplicate identical inputs
     * are harmless; changing an already received real input is a protocol failure.
     * Missing later inputs hold the most recent real input and are corrected in the same replay.
     */
    public long supply(long frame, int lane, int mask) {
        check(); checkLane(lane);
        if ((mask & ~65535) != 0) throw new IllegalArgumentException("Pad mask");
        var target = entry(frame); int bit = 1 << lane;
        if ((target.input.known() & bit) != 0) {
            if (target.input.pad(lane) != mask) throw new IllegalArgumentException("Conflicting real input");
            return -1;
        }
        long changed = -1;
        boolean first = true;
        for (var e : history.tailMap(frame, true).values()) {
            var old = e.input;
            if (!first && (old.known() & bit) != 0) break;
            if (old.pad(lane) != mask && changed < 0) changed = old.frame();
            e.input = new Input(old.frame(), lane == 0 ? mask : old.p1(), lane == 1 ? mask : old.p2(),
                    first ? old.known() | bit : old.known());
            first = false;
        }
        if (changed >= 0) replay(changed);
        return changed;
    }
    /** Host canonical commands may replace speculative inputs. One packet causes one replay. */
    public boolean canonical(List<Input> commands) {
        check();
        if (commands.isEmpty() || commands.size() > HISTORY) throw new IllegalArgumentException("Commands");
        long expected = commands.getFirst().frame(), changed = -1;
        for (var command : commands) {
            if (command.frame() != expected++) throw new IllegalArgumentException("Nonconsecutive commands");
            entry(command.frame()); // Validate the whole range before touching any entry.
        }
        for (var command : commands) {
            var e = entry(command.frame());
            if ((e.input.p1() != command.p1() || e.input.p2() != command.p2()) && changed < 0) changed = command.frame();
            e.input = command;
        }
        if (changed >= 0) replay(changed);
        return changed >= 0;
    }
    /** Discard only frames the authority has irrevocably confirmed; equality retains next's state. */
    public void discardBefore(long frame) {
        check();
        if (frame < oldest || frame > next) throw new IllegalArgumentException("Confirmation range");
        history.headMap(frame, false).clear(); oldest = frame;
    }
    public byte[] stateBefore(long frame) {
        check();
        return frame == next ? snapshot() : entry(frame).before.clone();
    }
    private void replay(long first) {
        core.restore(entry(first).before.clone());
        for (var e : history.tailMap(first, true).values()) {
            e.before = snapshot();
            core.step(e.input.p1(), e.input.p2(), false);
            replays++;
        }
    }
    private byte[] snapshot() {
        byte[] bytes = core.save();
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_STATE || stateSize != 0 && bytes.length != stateSize)
            throw new IllegalStateException("Rollback core snapshot size changed or exceeded budget");
        stateSize = bytes.length;
        return bytes.clone();
    }
    private Entry entry(long frame) {
        var e = history.get(frame);
        if (e == null) throw new IllegalArgumentException("Input outside retained rollback window");
        return e;
    }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rollback owner thread");
    }
    private static void checkLane(int lane) {
        if (lane < 0 || lane > 1) throw new IllegalArgumentException("Two digital lanes only");
    }
}
