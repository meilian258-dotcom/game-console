// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded in-memory child output. Contains no core-specific parsing or file logging. */
final class RetroArchProcessLog {
    private static final int MAX_CHARS = 8192;
    private static final String REDACTED = "[redacted]";
    private final String token;
    private final StringBuilder tail = new StringBuilder();
    private final CountDownLatch drained = new CountDownLatch(1);

    RetroArchProcessLog(String token) { this.token = token; }

    void read(InputStream source) {
        try (var reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) >= 0) append(new String(buffer, 0, length));
        } catch (IOException ignored) {
            // Child teardown may close its pipe. Preserve all output received before that.
        } finally {
            drained.countDown();
        }
    }

    synchronized void append(String text) {
        tail.append(text);
        // Join pipe chunks and redact before bounding: trimming first could expose
        // a secret suffix when the retention boundary cuts through a complete token.
        for (int at = tail.indexOf(token); at >= 0; at = tail.indexOf(token, at + REDACTED.length()))
            tail.replace(at, at + token.length(), REDACTED);
        if (tail.length() > MAX_CHARS) tail.delete(0, tail.length() - MAX_CHARS);
    }

    synchronized String snapshot() {
        return tail.toString();
    }

    /** Owner thread only: never wait without a deadline or from a UI diagnostic getter. */
    boolean awaitDrain(long timeoutMillis) {
        try {
            return drained.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
