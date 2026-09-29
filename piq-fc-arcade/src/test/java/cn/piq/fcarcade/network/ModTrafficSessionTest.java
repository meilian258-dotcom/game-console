package cn.piq.fcarcade.network;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModTrafficSessionTest {
    @Test void sameConnectionSamplingAndWorldDimensionChangesDoNotReset() {
        var clock=new AtomicLong();var lifecycle=new ModTrafficSession(()->new ModTrafficCounter(clock::get));var connection=new Object();
        var counter=lifecycle.connect(connection);counter.upload(12);counter.download(34);
        // A dimension change has the same underlying connection; no level identity enters the lifecycle.
        for(int i=0;i<5;i++){clock.addAndGet(1_000_000_000L);counter.sample();assertSame(counter,lifecycle.connect(connection));}
        assertEquals(46,counter.sample().totalBytes());
    }
    @Test void reconnectEvenToSameServerAndExplicitLogoutCreateFreshTotals() {
        var lifecycle=new ModTrafficSession();var sameServerConnection=new Object();
        var first=lifecycle.connect(sameServerConnection);first.upload(200);assertNull(lifecycle.connect(null));
        var second=lifecycle.connect(sameServerConnection);assertNotSame(first,second);assertEquals(0,second.sample().totalBytes());
        second.download(12);var other=lifecycle.connect(new Object());assertNotSame(second,other);assertEquals(0,other.sample().totalBytes());
    }
}
