// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

/** Same bounded asynchronous presentation contract for the default child and opt-in JNI. */
public interface GbaSession extends AutoCloseable {
    boolean isReady();
    String error();
    void offerInput(int mask);
    void clearInput();
    GbaProcessSession.Frame pollFrame();
    boolean awaitClosed(long timeoutMillis);
    @Override void close();
}
