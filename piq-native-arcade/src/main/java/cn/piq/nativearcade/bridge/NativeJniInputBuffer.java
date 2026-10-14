// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * JNI media input policy, deliberately separate from the fixed legacy helper.
 * A frame may consume a prefix only while no bit changes twice. Once a press
 * occurs, a later release starts another frame, preserving brief overlapping
 * chords such as held A -> A+B -> B. At most one direction-state change is
 * consumed per frame, so quarter circles/diagonals keep their captured path.
 * The result is
 * an actually submitted state, never an OR of unrelated snapshots. Every bit's
 * press/release order survives, including coin pulses and captured short taps.
 * Only eligible neighboring button states are compressed; their original time
 * spacing is not retained. This does not remove the cost of a genuinely slow core.
 */
public final class NativeJniInputBuffer {
    public static final int PORTS = 4, MAX_EDGES = 128;
    private static final int COIN = 4;
    private static final int DIRECTIONS = 0xF0;
    private record Edge(int mask, long offeredAt) {}
    public record PortSnapshot(int pending, long oldestWaitMillis, int peakPending, long maximumWaitMillis) {}
    public record Snapshot(List<PortSnapshot> ports, long mergedStates) {
        public Snapshot { ports = List.copyOf(ports); }
    }
    private final ArrayDeque<Edge>[] pending;
    private final int[] held = new int[PORTS], latest = new int[PORTS], peakPending = new int[PORTS];
    private final long[] maximumWait = new long[PORTS];
    private final LongSupplier clock;
    private long mergedStates;

    public NativeJniInputBuffer() { this(System::nanoTime); }
    @SuppressWarnings("unchecked") NativeJniInputBuffer(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        pending = new ArrayDeque[PORTS];
        for (int p = 0; p < PORTS; p++) pending[p] = new ArrayDeque<>();
    }

    /** Rejection is atomic across all ports; the owner retains its fail-closed policy. */
    public synchronized boolean offer(int p1, int p2, int p3, int p4) {
        int[] values = {p1, p2, p3, p4};
        for (int value : values) NativeInputPorts.checkMask(value);
        boolean changed = false;
        for (int p = 0; p < PORTS; p++) if (values[p] != latest[p]) {
            if (pending[p].size() >= MAX_EDGES) return false;
            changed = true;
        }
        if (!changed) return true;
        long now = clock.getAsLong();
        for (int p = 0; p < PORTS; p++) if (values[p] != latest[p]) {
            latest[p] = values[p];
            pending[p].addLast(new Edge(values[p], now));
            peakPending[p] = Math.max(peakPending[p], pending[p].size());
        }
        return true;
    }

    public synchronized int[] nextFrame() {
        long now = clock.getAsLong();
        for (int p = 0; p < PORTS; p++) {
            int changed = 0, consumed = 0;
            boolean pressed = false, directionChanged = false;
            while (!pending[p].isEmpty()) {
                Edge next = pending[p].peekFirst();
                int delta = held[p] ^ next.mask();
                int released = held[p] & ~next.mask();
                boolean direction = (delta & DIRECTIONS) != 0;
                if ((changed & delta) != 0 || pressed && released != 0 || directionChanged && direction) break;
                pending[p].removeFirst();
                maximumWait[p] = Math.max(maximumWait[p], age(now, next.offeredAt()));
                pressed |= (delta & next.mask()) != 0;
                directionChanged |= direction;
                held[p] = next.mask();
                changed |= delta;
                consumed++;
            }
            mergedStates += Math.max(0, consumed - 1);
        }
        return held.clone();
    }

    public synchronized void releasePort(int port) {
        NativeInputPorts.checkPort(port);
        rememberWait(port, clock.getAsLong());
        pending[port].clear();
        held[port] = latest[port] = 0;
    }

    /** Lifecycle release removes gameplay only; accepted coin edges keep their original ages. */
    public synchronized void releaseGameplayPortKeepingCoin(int port) {
        NativeInputPorts.checkPort(port);
        rememberWait(port, clock.getAsLong());
        held[port] &= COIN;
        int previous = held[port];
        var retained = new ArrayDeque<Edge>();
        for (Edge edge : pending[port]) {
            int coin = edge.mask() & COIN;
            if (coin != previous) {
                retained.addLast(new Edge(coin, edge.offeredAt()));
                previous = coin;
            }
        }
        pending[port].clear();
        pending[port].addAll(retained);
        latest[port] = previous;
    }

    /** History statistics survive focus resets, so opening diagnostics cannot hide an earlier backlog. */
    public synchronized void clear() {
        long now = clock.getAsLong();
        for (int p = 0; p < PORTS; p++) {
            rememberWait(p, now);
            pending[p].clear();
            held[p] = latest[p] = 0;
        }
    }

    /** Pure snapshot: never consumes an input, changes statistics, or calls the native core. */
    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        var ports = new ArrayList<PortSnapshot>(PORTS);
        for (int p = 0; p < PORTS; p++) {
            long waiting = oldestWait(p, now);
            ports.add(new PortSnapshot(pending[p].size(), waiting / 1_000_000,
                    peakPending[p], Math.max(maximumWait[p], waiting) / 1_000_000));
        }
        return new Snapshot(ports, mergedStates);
    }

    private void rememberWait(int port, long now) {
        maximumWait[port] = Math.max(maximumWait[port], oldestWait(port, now));
    }
    private long oldestWait(int port, long now) {
        Edge oldest = pending[port].peekFirst();
        return oldest == null ? 0 : age(now, oldest.offeredAt());
    }
    private static long age(long now, long before) { return Math.max(0, now - before); }
}
