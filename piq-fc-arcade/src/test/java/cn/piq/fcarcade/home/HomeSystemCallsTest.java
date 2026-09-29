package cn.piq.fcarcade.home;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSystemCallsTest {
    @Test void SuccessRunsExactlyOnce() {
        var calls=new HomeSystemCalls(); var count=new AtomicInteger();
        assertTrue(calls.invoke(new Object(),count::incrementAndGet,error->fail(error)));
        assertEquals(1,count.get());
    }
    @Test void FailureIsIsolatedAndGuardReleasedForLaterCalls() {
        var calls=new HomeSystemCalls(); Object owner=new Object(); var failures=new AtomicInteger();
        assertFalse(calls.invoke(owner,()->{throw new IllegalStateException("provider");},error->failures.incrementAndGet()));
        assertTrue(calls.invoke(owner,()->{},error->fail(error))); assertEquals(1,failures.get());
    }
    @Test void SameConsoleRecursiveStopCannotReenter() {
        var calls=new HomeSystemCalls(); Object owner=new Object(); var count=new AtomicInteger();
        assertTrue(calls.invoke(owner,()->assertFalse(calls.invoke(owner,count::incrementAndGet,error->fail(error))),error->fail(error)));
        assertEquals(0,count.get());
    }
    @Test void OtherConsoleCanStillStopDuringCallback() {
        var calls=new HomeSystemCalls(); Object one=new Object(),two=new Object(); var count=new AtomicInteger();
        assertTrue(calls.invoke(one,()->assertTrue(calls.invoke(two,count::incrementAndGet,error->fail(error))),error->fail(error)));
        assertEquals(1,count.get());
    }
    @Test void EvenAFailingErrorReporterReleasesOwnerGuard() {
        var calls=new HomeSystemCalls(); Object owner=new Object();
        assertThrows(IllegalArgumentException.class,()->calls.invoke(owner,()->{throw new IllegalStateException();},error->{throw new IllegalArgumentException();}));
        assertTrue(calls.invoke(owner,()->{},error->fail(error)));
    }
}
