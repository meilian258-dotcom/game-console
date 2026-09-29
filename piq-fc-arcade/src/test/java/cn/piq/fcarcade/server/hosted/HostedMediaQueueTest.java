package cn.piq.fcarcade.server.hosted;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedMediaQueueTest {
    private record Batch(boolean video,int sequence) {}
    @Test void slowServerTickDoesNotReplayOldFramesButKeepsAudioOrder() {
        var clock=new AtomicLong();var queue=new HostedMediaQueue<Batch>(8,Batch::video,clock::get);
        queue.offer(new Batch(true,1));queue.offer(new Batch(false,1));
        clock.set(50_000_000L);queue.offer(new Batch(true,2));queue.offer(new Batch(false,2));
        clock.set(300_000_000L);queue.offer(new Batch(true,3));
        assertEquals(new Batch(false,1),queue.poll());assertEquals(new Batch(false,2),queue.poll());
        assertEquals(new Batch(true,3),queue.poll());assertNull(queue.poll());
    }
    @Test void fullMailboxReplacesOldVideoAndNeverExceedsBound() {
        var queue=new HostedMediaQueue<Batch>(8,Batch::video,()->0L);
        queue.offer(new Batch(false,0));
        for(int i=0;i<1000;i++){assertTrue(queue.offer(new Batch(true,i)));assertTrue(queue.size()<=8);}
        assertEquals(new Batch(false,0),queue.poll());
        assertEquals(new Batch(true,993),queue.poll());
        Batch last=null;for(Batch item;(item=queue.poll())!=null;)last=item;
        assertEquals(new Batch(true,999),last);
    }
    @Test void fullAudioQueueRejectsVideoAndKeepsNewestBoundedAudio() {
        var queue=new HostedMediaQueue<Batch>(4,Batch::video,()->0L);
        for(int i=0;i<4;i++)queue.offer(new Batch(false,i));
        assertFalse(queue.videoRoom());assertFalse(queue.offer(new Batch(true,1)));
        assertTrue(queue.offer(new Batch(false,4)));assertEquals(new Batch(false,1),queue.poll());assertEquals(3,queue.size());
        queue.clear();assertNull(queue.poll());assertTrue(queue.videoRoom());
    }
    @Test void videoPrunesOnReadEvenWhenProducerStopped() {
        var clock=new AtomicLong();var queue=new HostedMediaQueue<Batch>(8,Batch::video,clock::get);
        queue.offer(new Batch(true,0));clock.set(HostedMediaQueue.MAX_VIDEO_AGE_NANOS+1);
        assertNull(queue.poll());assertEquals(0,queue.size());
    }
}
