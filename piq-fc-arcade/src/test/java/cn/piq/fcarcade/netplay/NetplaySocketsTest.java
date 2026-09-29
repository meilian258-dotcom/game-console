package cn.piq.fcarcade.netplay;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetplaySocketsTest {
    @Test void retiredOwnerClosesLateSocketInsteadOfLeaking()throws Exception {
        var owner=new NetplaySockets();owner.close();var socket=new Socket();
        assertThrows(IOException.class,()->owner.own(socket));assertTrue(socket.isClosed());
    }
    @Test void closesListenerAndPreAuthenticationSocket()throws Exception {
        var owner=new NetplaySockets();var listener=owner.own(new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()));
        var pending=owner.own(new Socket());owner.close();
        assertTrue(listener.isClosed());assertTrue(pending.isClosed());
    }
    @Test void releaseIsIdempotentAndDoesNotAccumulateClosedResources()throws Exception {
        var owner=new NetplaySockets();AtomicInteger count=new AtomicInteger();Closeable value=count::incrementAndGet;
        owner.own(value);owner.release(value);owner.release(value);owner.close();assertEquals(1,count.get());
    }
    @Test void closingOneBadResourceDoesNotSkipTheOthers()throws Exception {
        var owner=new NetplaySockets();owner.own(()->{throw new IOException("test");});AtomicInteger count=new AtomicInteger();owner.own((Closeable)count::incrementAndGet);
        owner.close();assertEquals(1,count.get());
    }
    @Test void concurrentRetirementNeverMissesLateOwnership()throws Exception {
        for(int i=0;i<100;i++){
            var owner=new NetplaySockets();AtomicInteger count=new AtomicInteger();var gate=new CountDownLatch(1);
            var thread=new Thread(()->{try{gate.await();owner.own((Closeable)count::incrementAndGet);}catch(IOException expected){}catch(InterruptedException bad){Thread.currentThread().interrupt();}});
            thread.start();gate.countDown();owner.close();thread.join(1000);assertFalse(thread.isAlive());assertEquals(1,count.get());
        }
    }
    @Test void cancelBeforeStartHasNoSideEffects(){
        var reads=new AtomicInteger();var process=new NetplayProcess(new NetplayProcess.Grant(1,java.util.UUID.randomUUID(),true,true),()->{reads.incrementAndGet();return null;},c->{});
        process.close();process.start();assertFalse(process.ready());assertEquals("已结束",process.status());assertEquals(0,reads.get());assertNull(process.error());
    }
}
