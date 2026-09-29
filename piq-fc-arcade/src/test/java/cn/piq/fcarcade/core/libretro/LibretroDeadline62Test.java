package cn.piq.fcarcade.core.libretro;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibretroDeadline62Test {
    private static final class ObservedSocket extends Socket {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }
    @Test void timeoutClosesOnlyCapturedConnectionAndLateCompletionStillFails() throws Exception {
        var expired = new ObservedSocket(); var unrelated = new ObservedSocket();
        var deadline = new LibretroNesCore.Deadline(null, expired, 20);
        assertTrue(expired.closed.await(3, TimeUnit.SECONDS));
        assertThrows(SocketTimeoutException.class, deadline::close);
        assertEquals(1, expired.closes.get()); assertEquals(0, unrelated.closes.get());
    }
    @Test void completedOperationCannotBeClosedByCancelledTimer() throws Exception {
        var connection = new ObservedSocket();
        var deadline = new LibretroNesCore.Deadline(null, connection, 40);
        deadline.close(); deadline.close();
        assertFalse(connection.closed.await(120, TimeUnit.MILLISECONDS));
        assertEquals(0, connection.closes.get());
    }
    @Test void cancelledPriorOperationDoesNotInterfereWithNextDeadline() throws Exception {
        var connection = new ObservedSocket();
        var previous = new LibretroNesCore.Deadline(null, connection, 20); previous.close();
        var current = new LibretroNesCore.Deadline(null, connection, 180);
        assertFalse(connection.closed.await(60, TimeUnit.MILLISECONDS));
        assertTrue(connection.closed.await(3, TimeUnit.SECONDS));
        assertThrows(SocketTimeoutException.class, current::close); assertEquals(1, connection.closes.get());
    }
    @Test void hardDeadlineInterruptsSocketWritesWhenPeerStopsReading() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var writer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 var peer = server.accept()) {
                writer.setSendBufferSize(1024); peer.setReceiveBufferSize(1024);
                assertThrows(IOException.class, () -> {
                    try (var deadline = new LibretroNesCore.Deadline(null, writer, 100)) {
                        byte[] bytes = new byte[1024 * 1024];
                        for (int i = 0; i < 64; i++) writer.getOutputStream().write(bytes);
                    }
                });
                assertTrue(writer.isClosed());
            }
        });
    }
}
