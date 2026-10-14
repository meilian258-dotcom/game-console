// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetroArchProcessLogTest {
    @Test void tailIsBoundedAndKeepsFinalRejectionAfterReaderDrain() throws Exception {
        var log = new RetroArchProcessLog("private-ipc-token");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] output = ("start\n" + "x".repeat(9000) + "\nfinal core rejection\n").getBytes(StandardCharsets.UTF_8);
        var source = new ByteArrayInputStream(output) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Reader test release timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new AssertionError(failure);
                }
                return super.read(bytes, offset, length);
            }
        };
        try (var reader = Executors.newSingleThreadExecutor()) {
            var read = reader.submit(() -> log.read(source));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertFalse(log.awaitDrain(0));
                assertEquals("", log.snapshot(), "Diagnostic getters do not wait on child output");
            } finally { release.countDown(); }
            assertTrue(log.awaitDrain(1000));
            read.get(1, TimeUnit.SECONDS);
            assertEquals(8192, log.snapshot().length());
            assertTrue(log.snapshot().endsWith("final core rejection\n"));
            assertFalse(log.snapshot().contains("start\n"));
        }
    }

    @Test void unicodeAndTokenSplitAcrossPipeReadsRemainIntactAndRedacted() {
        String token = "abcdefghijklmnopqrstuv0123456789";
        var log = new RetroArchProcessLog(token);
        var source = new FilterInputStream(new ByteArrayInputStream(("缺少文件 " + token + "；已结束").getBytes(StandardCharsets.UTF_8))) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        log.read(source);
        assertTrue(log.awaitDrain(0));
        assertEquals("缺少文件 [redacted]；已结束", log.snapshot());
    }

    @Test void drainIsBoundedAndDoesNotSwallowInterrupts() {
        var log = new RetroArchProcessLog("token");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(log.awaitDrain(10)));
        Thread.currentThread().interrupt();
        try {
            assertFalse(log.awaitDrain(1000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void retentionBoundaryCannotCutThroughASecretBeforeRedaction() {
        String token = "abcdefghijklmnopqrstuv0123456789";
        for (int removed = 1; removed < token.length(); removed++) {
            var log = new RetroArchProcessLog(token);
            log.append(token.substring(0, 11));
            log.append(token.substring(11) + "x".repeat(8192 - token.length() + removed));
            String snapshot = log.snapshot();
            assertTrue(snapshot.length() <= 8192);
            assertFalse(snapshot.contains(token.substring(removed)), "Leaked retained secret suffix at " + removed);
            assertTrue(snapshot.endsWith("x".repeat(64)));
        }
    }

    @Test void closedOutputStillCompletesTheDrainAndRetainsEarlierText() {
        var log = new RetroArchProcessLog("token");
        log.append("earlier output");
        log.read(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("pipe closed"); }
        });
        assertTrue(log.awaitDrain(0));
        assertEquals("earlier output", log.snapshot());
    }
}
