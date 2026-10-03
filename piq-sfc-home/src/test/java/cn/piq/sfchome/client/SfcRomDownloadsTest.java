package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcRomDownloadsTest {
    private final Object connection=new Object();
    private final SfcRomDownloads queue=new SfcRomDownloads();
    private final List<String> sent=new ArrayList<>();
    private static final String A="a".repeat(64),B="b".repeat(64);
    SfcRomDownloadsTest(){queue.connection(connection);}
    private SfcHomeNetwork.RomChunk chunk(String hash,int offset){return new SfcHomeNetwork.RomChunk(hash,65536,offset,new byte[32768]);}
    private void tick(long t){queue.tick(connection,t,sent::add);}
    @Test void observerAndControlForSameRomShareOneTransferAndIndependentCancellation(){
        var observer=queue.request(A,connection);var control=queue.request(A,connection);tick(0);
        assertEquals(List.of(A),sent);queue.chunk(connection,chunk(A,0));observer.cancel();
        queue.chunk(connection,chunk(A,32768));assertTrue(observer.result.isCancelled());assertEquals(65536,control.result.join().length);
    }
    @Test void differentScreensAndControlSerializeWithoutDroppingServerCooldown(){
        var observer=queue.request(A,connection);var control=queue.request(B,connection);tick(0);
        queue.chunk(connection,chunk(A,0));queue.chunk(connection,chunk(A,32768));assertTrue(observer.result.isDone());
        tick(39);tick(59);assertEquals(List.of(A),sent);tick(60);assertEquals(List.of(A,B),sent);
        queue.chunk(connection,chunk(B,0));queue.chunk(connection,chunk(B,32768));assertTrue(control.result.isDone());
    }
    @Test void lastCancelledReaderDrainsActiveTransferBeforeAnotherHash(){
        var observer=queue.request(A,connection);var control=queue.request(B,connection);tick(0);observer.cancel();tick(1);
        assertEquals(List.of(A),sent);queue.chunk(connection,chunk(A,0));queue.chunk(connection,chunk(A,32768));tick(60);
        assertEquals(List.of(A,B),sent);assertFalse(control.result.isDone());
    }
    @Test void delayedOldConnectionPacketsAndReaderCancellationCannotReleaseFreshQueue(){
        var old=queue.request(A,connection);tick(0);var next=new Object();queue.connection(next);var fresh=queue.request(A,next);
        old.cancel();queue.tick(next,60,sent::add);queue.chunk(connection,chunk(A,0));assertEquals(0,fresh.offset());
        queue.chunk(next,chunk(A,0));queue.chunk(next,chunk(A,32768));assertTrue(old.result.isCancelled());assertTrue(fresh.result.isDone());
    }
    @Test void retriesAreBoundedAndPartialTransfersAreNeverRestarted(){
        var unavailable=queue.request(A,connection);tick(0);tick(59);tick(60);tick(120);tick(180);
        assertEquals(List.of(A,A,A),sent);assertTrue(unavailable.result.isCompletedExceptionally());
        var partial=queue.request(B,connection);tick(181);queue.chunk(connection,chunk(B,0));tick(242);assertEquals(4,sent.size());
        tick(382);assertTrue(partial.result.isCompletedExceptionally());assertEquals(4,sent.size());
    }
    @Test void boundedQueueAndReaderLimitNeverEvictActiveReaders(){
        var first=queue.request(A,connection);for(int i=1;i<SfcRomDownloads.READERS;i++)queue.request(A,connection);
        assertThrows(IllegalStateException.class,()->queue.request(A,connection));
        for(char c:new char[]{'b','c','d'})queue.request(String.valueOf(c).repeat(64),connection);
        assertThrows(IllegalStateException.class,()->queue.request("e".repeat(64),connection));assertFalse(first.result.isDone());
    }
    @Test void badOffsetFailsOnlyItsTransferAndFollowingHashCanStillFinish(){
        var broken=queue.request(A,connection);var good=queue.request(B,connection);tick(0);
        queue.chunk(connection,chunk(A,32768));assertTrue(broken.result.isCompletedExceptionally());tick(60);
        queue.chunk(connection,chunk(B,0));queue.chunk(connection,chunk(B,32768));assertEquals(65536,good.result.join().length);
    }
    @Test void transportErrorReleasesWaitersAndLogoutCancelsQueuedReaders(){
        var bad=queue.request(A,connection);var waiting=queue.request(B,connection);
        queue.tick(connection,0,hash->{throw new IllegalStateException("transport closed");});assertTrue(bad.result.isCompletedExceptionally());
        queue.connection(null);assertTrue(waiting.result.isCancelled());assertThrows(IllegalStateException.class,()->queue.request(A,connection));
    }
}
