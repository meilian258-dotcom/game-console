package cn.piq.fcarcade.server.hosted;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostedServerLimitsTest {
    @Test void allAdaptersSharePendingAndClosingCapacity() {
        var pool=new HostedServerLimits.Pool();
        var fc=pool.acquire(2);var sfc=pool.acquire(2);
        assertNotNull(fc);assertNotNull(sfc);assertNull(pool.acquire(2));
        assertEquals(2,pool.active()); // Requesting worker close does not release its lease.
        fc.close();assertEquals(1,pool.active());fc.close();assertEquals(1,pool.active());
        var cabinet=pool.acquire(2);assertNotNull(cabinet);assertNull(pool.acquire(2));
        sfc.close();cabinet.close();assertEquals(0,pool.active());
    }
    @Test void loweringLimitDoesNotRevokeExistingWorkers() {
        var pool=new HostedServerLimits.Pool();var a=pool.acquire(2);var b=pool.acquire(2);
        assertNull(pool.acquire(1));a.close();assertNull(pool.acquire(1));b.close();
        assertNotNull(pool.acquire(1));
    }
    @Test void raceCannotOverbook() throws Exception {
        var pool=new HostedServerLimits.Pool();var success=new AtomicInteger();
        var leases=new ConcurrentLinkedQueue<HostedServerLimits.Lease>();
        try(var executor=Executors.newFixedThreadPool(8)) {
            var ready=new CountDownLatch(1);var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<64;i++)tasks.add(executor.submit(()->{ready.await();var lease=pool.acquire(2);if(lease!=null){success.incrementAndGet();leases.add(lease);}return null;}));
            ready.countDown();for(var task:tasks)task.get(5,TimeUnit.SECONDS);
        }
        assertEquals(2,success.get());assertEquals(2,pool.active());
        leases.forEach(HostedServerLimits.Lease::close);assertEquals(0,pool.active());
    }
    @Test void everyRecipientConsumesSharedBudget() {
        var pool=new HostedServerLimits.Pool();
        assertTrue(pool.reserve(131072,262144,0));assertTrue(pool.reserve(131072,262144,0));
        assertFalse(pool.reserve(1,262144,0));assertFalse(pool.reserve(1,262144,-1));
        assertTrue(pool.reserve(131072,262144,500_000_000L));
        assertFalse(pool.reserve(262145,262144,1_000_000_000L));
        assertFalse(pool.reserve(0,262144,1_000_000_000L));
    }
    @Test void fixedFirstRoomCannotStarveLaterRoomForever() {
        var pool=new HostedServerLimits.Pool();var first=pool.acquire(2);var later=pool.acquire(2);
        int a=0,b=0;
        for(int tick=0;tick<400;tick++){
            long now=tick*50_000_000L;
            if(pool.reserve(first,65536,262144,now))a++;
            if(pool.reserve(later,65536,262144,now))b++;
        }
        assertTrue(a>30);assertTrue(b>30);assertTrue(Math.abs(a-b)<=4,a+" / "+b);
        later.close();assertFalse(pool.reserve(later,1,262144,30_000_000_000L));
        var foreign=new HostedServerLimits.Pool().acquire(2);
        assertFalse(pool.reserve(foreign,1,262144,30_000_000_000L));first.close();foreign.close();
    }
}
