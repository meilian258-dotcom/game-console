package cn.piq.fcarcade.session;

import java.util.ArrayDeque;

/** Bounded absolute-state edges, consumed once per emulated frame, never OR-merged. */
public final class ControllerInputTransitions {
    public static final int MAX_PENDING = 32;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final int capacity;
    private int submitted, applied;
    private boolean blocked;

    public ControllerInputTransitions() { this(MAX_PENDING); }
    public ControllerInputTransitions(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    /** False means overload/invalid input: stay neutral until a genuine release. */
    public synchronized boolean offer(int mask) {
        if ((mask & ~255) != 0) { failClosed(); return false; }
        if (blocked) {
            if (mask == 0) clear();
            return mask == 0;
        }
        if (submitted == mask) return true;
        if (pending.size() >= capacity) { failClosed(); return false; }
        submitted = mask;
        pending.addLast(mask);
        return true;
    }

    public synchronized int nextFrame() {
        if (!pending.isEmpty()) applied = pending.removeFirst();
        return applied;
    }

    /** Lifecycle release is not an ordinary queued key-up. */
    public synchronized void clear() {
        pending.clear(); submitted = applied = 0; blocked = false;
    }

    public synchronized void failClosed() {
        pending.clear(); submitted = applied = 0; blocked = true;
    }

    public synchronized int pendingCount() { return pending.size(); }
}
