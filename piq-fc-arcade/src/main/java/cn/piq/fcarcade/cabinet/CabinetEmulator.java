// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroEmulator;
import cn.piq.retro.api.RetroFrame;

/** Asynchronous core boundary. Methods never block the game/render thread. Masks use libretro's 12-key order. */
public interface CabinetEmulator extends AutoCloseable {
    boolean isReady();
    String error();
    void offerInput(int p1, int p2);
    default int maxPlayers() { return 2; }
    default void offerInputs(int p1, int p2, int p3, int p4) {
        if (p3 != 0 || p4 != 0) throw new IllegalArgumentException("This emulator exposes only two controller ports");
        offerInput(p1, p2);
    }
    default void releasePort(int port) {
        throw new UnsupportedOperationException("This emulator does not support isolated controller release");
    }
    default boolean supportsCoinPreservingRelease() { return false; }
    default void releaseGameplayPortKeepingCoin(int port) {
        throw new UnsupportedOperationException("This emulator cannot preserve paid coin edges during gameplay release");
    }
    void clearInput();
    CabinetFrame pollFrame();
    @Override void close();

    /**
     * Binary-compatible bridge for existing addon implementations. Calls remain
     * synchronous delegations to their existing non-blocking API; no worker,
     * queue, input conversion, pixel copy or audio copy is introduced here.
     */
    default RetroEmulator asRetro() {
        CabinetEmulator legacy = this;
        return new RetroEmulator() {
            @Override public boolean isReady() { return legacy.isReady(); }
            @Override public String error() { return legacy.error(); }
            @Override public void offerInput(int p1, int p2) { legacy.offerInput(p1, p2); }
            @Override public int maxPlayers() { return legacy.maxPlayers(); }
            @Override public void offerInputs(int p1,int p2,int p3,int p4) { legacy.offerInputs(p1,p2,p3,p4); }
            @Override public void releasePort(int port) { legacy.releasePort(port); }
            @Override public boolean supportsCoinPreservingRelease() { return legacy.supportsCoinPreservingRelease(); }
            @Override public void releaseGameplayPortKeepingCoin(int port) { legacy.releaseGameplayPortKeepingCoin(port); }
            @Override public void clearInput() { legacy.clearInput(); }
            @Override public RetroFrame pollFrame() {
                CabinetFrame frame = legacy.pollFrame();
                return frame == null ? null : new RetroFrame(frame.width(), frame.height(), frame.abgr(),
                        frame.displayAspect(), frame.rotation(), frame.pcm48k());
            }
            @Override public void close() { legacy.close(); }
        };
    }
}
