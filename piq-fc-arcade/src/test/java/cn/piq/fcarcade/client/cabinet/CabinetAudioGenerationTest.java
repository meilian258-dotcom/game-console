package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real CabinetAudio worker/queue; only the physical JavaSound endpoint is controlled. */
class CabinetAudioGenerationTest {
    static final class Device {
        final List<String> events=new CopyOnWriteArrayList<>();
        final List<byte[]> writes=new CopyOnWriteArrayList<>();
        final List<Thread> callers=new CopyOnWriteArrayList<>();
        final CountDownLatch started=new CountDownLatch(1),closed=new CountDownLatch(1);
        final AtomicInteger available=new AtomicInteger(38400),writeCount=new AtomicInteger(),flushCount=new AtomicInteger();
        volatile CountDownLatch writeEntered,writeGo,flushEntered,flushGo;
        volatile boolean failWrite;
        final SourceDataLine line=(SourceDataLine)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SourceDataLine.class},(p,m,args)->{
            callers.add(Thread.currentThread());
            switch(m.getName()){
                case "open":events.add("open");return null;
                case "start":events.add("start");started.countDown();return null;
                case "stop":events.add("stop");return null;
                case "flush":
                    if(flushCount.incrementAndGet()==1&&flushEntered!=null){flushEntered.countDown();assertTrue(flushGo.await(3,TimeUnit.SECONDS));}
                    events.add("flush");return null;
                case "close":events.add("close");closed.countDown();return null;
                case "available":return available.get();
                case "write":
                    int n=writeCount.incrementAndGet();
                    if(n==1&&writeEntered!=null){writeEntered.countDown();assertTrue(writeGo.await(3,TimeUnit.SECONDS));}
                    if(failWrite)throw new IllegalStateException("controlled device failure");
                    byte[] bytes=Arrays.copyOfRange((byte[])args[0],(int)args[1],(int)args[1]+(int)args[2]);
                    writes.add(bytes);events.add("write:"+(bytes[0]&255));return bytes.length;
                case "toString":return "ControlledSourceDataLine";
                default:throw new AssertionError("Unexpected device call "+m.getName());
            }
        });
        void blockFirstWrite(){writeEntered=new CountDownLatch(1);writeGo=new CountDownLatch(1);}
        void release(){if(writeGo!=null)writeGo.countDown();if(flushGo!=null)flushGo.countDown();}
        void allCallsOnWorker(){assertFalse(callers.isEmpty());assertTrue(callers.stream().allMatch(t->t!=Thread.currentThread()&&t.getName().equals("PIQ-Cabinet-Audio")));}
    }
    static short[] pcm(int value,int length){short[] result=new short[length];Arrays.fill(result,(short)value);return result;}
    static CabinetAudio audio(Device device){var audio=new CabinetAudio(format->device.line);audio.gain(1);return audio;}
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(1);assertTrue(condition.getAsBoolean());}
    static void quick(Runnable operation){long start=System.nanoTime();operation.run();assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500),"Caller waited for the device");}
    static void finish(CabinetAudio audio,Device device)throws Exception{device.release();audio.close();assertTrue(device.closed.await(3,TimeUnit.SECONDS));device.allCallsOnWorker();}

    @Test void normalPlaybackCopiesPcmAndWritesBoundedStereoChunks()throws Exception{
        var d=new Device();d.available.set(0);var a=audio(d);
        try{short[] input=pcm(0x1234,2400);a.offer(input);Arrays.fill(input,(short)0);d.available.set(1000);
            await(()->d.writes.stream().mapToInt(b->b.length).sum()==4800);
            for(byte[] bytes:d.writes){assertTrue(bytes.length<=1000);assertEquals(0,bytes.length%4);for(int i=0;i<bytes.length;i+=2){assertEquals(0x34,bytes[i]&255);assertEquals(0x12,bytes[i+1]&255);}}
        }finally{finish(a,d);}
    }
    @Test void queueOverflowDropsOldestAndIsBoundedToFourBeforeDeviceOpen()throws Exception{
        var d=new Device();var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var a=new CabinetAudio(format->{entered.countDown();assertTrue(go.await(3,TimeUnit.SECONDS));return d.line;});a.gain(1);
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));for(int i=1;i<=12;i++)a.offer(pcm(i,2));go.countDown();await(()->d.writes.size()==4);
            assertEquals(List.of(9,10,11,12),d.writes.stream().map(b->b[0]&255).toList());
        }finally{go.countDown();finish(a,d);}
    }
    @Test void resetBeforeDeviceOpenDropsOldQueueAndRetainsNewGeneration()throws Exception{
        var d=new Device();var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var a=new CabinetAudio(format->{entered.countDown();assertTrue(go.await(3,TimeUnit.SECONDS));return d.line;});a.gain(1);
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));a.offer(pcm(11,2));quick(a::reset);a.offer(pcm(22,2));go.countDown();await(()->d.writes.size()==1);
            assertEquals(22,d.writes.getFirst()[0]&255);assertTrue(d.events.indexOf("flush")<d.events.indexOf("write:22"));
        }finally{go.countDown();finish(a,d);}
    }
    @Test void resetDuringWriteFlushesItsCompletionBeforeNewPcmAndDiscardsOldTail()throws Exception{
        var d=new Device();d.blockFirstWrite();var a=audio(d);
        try{a.offer(pcm(11,32768));assertTrue(d.writeEntered.await(2,TimeUnit.SECONDS));a.offer(pcm(12,2));quick(a::reset);a.offer(pcm(22,2));d.writeGo.countDown();await(()->d.writes.size()==2);
            assertEquals(1920,d.writes.getFirst().length);assertEquals(22,d.writes.getLast()[0]&255);
            assertTrue(d.events.indexOf("write:11")<d.events.indexOf("flush"));assertTrue(d.events.indexOf("flush")<d.events.indexOf("write:22"));
        }finally{finish(a,d);}
    }
    @Test void aSecondResetDuringDeviceFlushCannotPublishIntermediateGeneration()throws Exception{
        var d=new Device();d.blockFirstWrite();d.flushEntered=new CountDownLatch(1);d.flushGo=new CountDownLatch(1);var a=audio(d);
        try{a.offer(pcm(11,2));assertTrue(d.writeEntered.await(2,TimeUnit.SECONDS));a.reset();a.offer(pcm(22,2));d.writeGo.countDown();assertTrue(d.flushEntered.await(2,TimeUnit.SECONDS));
            quick(a::reset);a.offer(pcm(33,2));d.flushGo.countDown();await(()->d.writes.size()==2);
            assertEquals(List.of(11,33),d.writes.stream().map(b->b[0]&255).toList());assertTrue(d.flushCount.get()>=2);
        }finally{finish(a,d);}
    }
    @Test void resetWhileDeviceHasNoSpaceDiscardsAlreadyDequeuedBlock()throws Exception{
        var d=new Device();d.available.set(0);var a=audio(d);
        try{a.offer(pcm(11,32768));assertTrue(d.started.await(2,TimeUnit.SECONDS));Thread.sleep(25);quick(a::reset);a.offer(pcm(22,2));await(()->d.flushCount.get()>0);assertTrue(d.writes.isEmpty());d.available.set(4);await(()->d.writes.size()==1);assertEquals(22,d.writes.getFirst()[0]&255);
        }finally{finish(a,d);}
    }
    @Test void closeDuringWriteReturnsWithoutDeviceCallsAndCannotCloseNextSession()throws Exception{
        var old=new Device();old.blockFirstWrite();var a=audio(old);var fresh=new Device();var b=audio(fresh);
        try{a.offer(pcm(11,32768));assertTrue(old.writeEntered.await(2,TimeUnit.SECONDS));quick(a::close);a.offer(pcm(12,2));quick(a::reset);
            b.offer(pcm(22,2));await(()->fresh.writes.size()==1);assertEquals(1,fresh.closed.getCount());old.writeGo.countDown();assertTrue(old.closed.await(2,TimeUnit.SECONDS));assertEquals(1,old.writes.size());assertEquals(1,fresh.closed.getCount());
            b.offer(pcm(33,2));await(()->fresh.writes.size()==2);
        }finally{finish(a,old);finish(b,fresh);}
    }
    @Test void closeDuringDeviceCreationDoesNotOpenLateReturnedDevice()throws Exception{
        var d=new Device();var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var a=new CabinetAudio(format->{entered.countDown();assertTrue(go.await(3,TimeUnit.SECONDS));return d.line;});
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));a.offer(pcm(11,2));quick(a::close);go.countDown();assertTrue(d.closed.await(2,TimeUnit.SECONDS));assertFalse(d.events.contains("open"));assertTrue(d.writes.isEmpty());
        }finally{go.countDown();finish(a,d);}
    }
    @Test void failingDeviceIsOptionalAndCannotThrowOnResetOrOffer()throws Exception{
        var d=new Device();d.failWrite=true;var a=audio(d);
        try{a.offer(pcm(11,2));assertTrue(d.closed.await(2,TimeUnit.SECONDS));assertDoesNotThrow(a::reset);assertDoesNotThrow(()->a.offer(pcm(22,2)));assertEquals(1,d.writeCount.get());
        }finally{finish(a,d);}
    }
    @Test void repeatedResetsKeepOnlyFinalGenerationAfterInFlightWrite()throws Exception{
        var d=new Device();d.blockFirstWrite();var a=audio(d);
        try{a.offer(pcm(11,32768));assertTrue(d.writeEntered.await(2,TimeUnit.SECONDS));for(int i=0;i<500;i++){a.offer(pcm(12,2));a.reset();}a.offer(pcm(44,2));d.writeGo.countDown();await(()->d.writes.size()==2);assertEquals(List.of(11,44),d.writes.stream().map(b->b[0]&255).toList());
        }finally{finish(a,d);}
    }
}
