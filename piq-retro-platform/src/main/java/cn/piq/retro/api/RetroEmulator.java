// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.api;

/**
 * Bounded asynchronous emulator boundary, independent of Minecraft or any core.
 * Calls must not wait for a worker or native process on the game/render thread.
 * Factories perform blocking preparation separately; core calls remain on the
 * owning worker. A missing frame is represented by {@code null}.
 *
 * <p>Input retains the existing 12-bit libretro order: B, Y, Select, Start,
 * Up, Down, Left, Right, A, X, L, R. Port arguments are emulator ports 1 and 2,
 * not authorization to assign local devices or remote players to those ports.
 * Implementations retain their existing queueing and timing policy.</p>
 */
public interface RetroEmulator extends AutoCloseable {
    boolean isReady();
    String error();
    void offerInput(int p1, int p2);
    /** Number of digital controller ports exposed by this adapter, not a promise about every ROM. */
    default int maxPlayers() { return 2; }
    /** Legacy adapters fail explicitly instead of silently discarding remote players 3 and 4. */
    default void offerInputs(int p1, int p2, int p3, int p4) {
        if (p3 != 0 || p4 != 0) throw new IllegalArgumentException("This emulator exposes only two controller ports");
        offerInput(p1, p2);
    }
    /** Lifecycle release must clear this port's held AND queued states without clearing another player. */
    default void releasePort(int port) {
        throw new UnsupportedOperationException("This emulator does not support isolated controller release");
    }
    /** Optional paid-coin safety: immediately discard gameplay history, preserving only queued Select/coin edges. */
    default boolean supportsCoinPreservingRelease() { return false; }
    /** Never fall back to releasePort or an ordinary queued zero: either loses paid coins or replays stale controls. */
    default void releaseGameplayPortKeepingCoin(int port) {
        throw new UnsupportedOperationException("This emulator cannot preserve paid coin edges during gameplay release");
    }
    void clearInput();
    RetroFrame pollFrame();
    @Override void close();
}
