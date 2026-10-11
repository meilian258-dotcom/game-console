package cn.piq.replaycompat.audio;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.*;
import static cn.piq.replaycompat.audio.AudioTestSupport.*;

public final class JavaSoundSinkTest {
    public static void main(String[] args) throws Exception {
        Device fake=new Device();JavaSoundSink sink=new JavaSoundSink(format->fake.line);
        Thread.sleep(20);check(fake.calls.isEmpty(),"no hardware opened before real PCM exists");
        sink.reset(1);check(!sink.offer(0,pcm(480,2,1)),"stale generation rejected");
        check(!sink.offer(1,new byte[3]),"unaligned PCM rejected");
        check(!sink.offer(1,new byte[15364]),"oversized lookahead rejected");
        check(sink.offer(1,pcm(960,2,1234)),"bounded valid PCM accepted");
        check(fake.writeEntered.await(2,TimeUnit.SECONDS),"device worker reaches real write method");
        long start=System.nanoTime();sink.reset(2);
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"reset never waits for a blocked driver write");
        check(!sink.offer(1,pcm(48,2,999)),"old decoder result rejected after reset");
        fake.releaseWrite.countDown();
        await(()->fake.flushes>0,"worker flushes after in-flightwrite returns");
        Thread.sleep(20);check(fake.written.size()==1,"old chunk tail never resumes after reset");
        check(fake.written.get(0).length==1920,"single hardware write capped10ms");
        check(sink.offer(2,pcm(480,2,4321)),"new generation resumes");
        await(()->fake.written.size()==2,"new PCM reaches device");
        check(sample(fake.written.get(1),0)==4321,"exact already-volume-adjusted samples preserved");
        start=System.nanoTime();sink.close();
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"close never joins device thread");
        await(()->fake.closed,"worker closes audio line");
        check(!sink.offer(2,pcm(48,2,999)),"closed device rejects offers");
        check(fake.calls.stream().allMatch(value->value.startsWith("PIQ-Replay-Audio-Device:")),"every line operation belongs to audio daemon");
        check(fake.openedBuffer==3840,"device buffer bounded20ms");
        check(sink.offerResult(2,new byte[3])==PcmSink.OfferResult.INVALID,"invalid PCM has explicit result");
        check(sink.offerResult(2,pcm(48,2,1))==PcmSink.OfferResult.DEVICE_FAILED,"closed output is not queue congestion");
        deviceRecovery();
        closeDuringDriverCall();
        System.out.println("JavaSoundSinkTest: "+checks+" checks passed");
    }
    private static void closeDuringDriverCall() throws Exception {
        CountDownLatch availableEntered=new CountDownLatch(1),availableRelease=new CountDownLatch(1);
        AtomicInteger lateWrites=new AtomicInteger();CountDownLatch deviceClosed=new CountDownLatch(1);
        SourceDataLine blocked=(SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class},(proxy,method,args)->switch(method.getName()){
                    case "available"->{availableEntered.countDown();if(!availableRelease.await(3,TimeUnit.SECONDS))throw new AssertionError("available gate timeout");yield 1920;}
                    case "write"->{lateWrites.incrementAndGet();yield args[2];}
                    case "close"->{deviceClosed.countDown();yield null;}
                    case "open","start","stop","flush"->null;
                    default->throw new UnsupportedOperationException(method.getName());
                });
        JavaSoundSink sink=new JavaSoundSink(format->blocked);sink.offer(0,pcm(480,2,5555));
        check(availableEntered.await(2,TimeUnit.SECONDS),"device driver available gate reached");
        long start=System.nanoTime();sink.close();
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"close does not wait for blocked available");
        availableRelease.countDown();check(deviceClosed.await(2,TimeUnit.SECONDS),"device closes when driver returns");
        check(lateWrites.get()==0,"blocked available cannot release latePCM after close");

        CountDownLatch openEntered=new CountDownLatch(1),openRelease=new CountDownLatch(1);PacedDevice opened=new PacedDevice();
        JavaSoundSink opening=new JavaSoundSink(format->{openEntered.countDown();if(!openRelease.await(3,TimeUnit.SECONDS))throw new AssertionError("open gate timeout");return opened.line;});
        opening.offer(0,pcm(480,2,6666));check(openEntered.await(2,TimeUnit.SECONDS),"factory open gate reached");
        opening.close();openRelease.countDown();await(opened::closed,"late factory result released on daemon");
        check(opened.writes()==0,"late open never plays after close");
    }
    private static void deviceRecovery() throws Exception {
        AtomicInteger attempts=new AtomicInteger();PacedDevice device=new PacedDevice();
        JavaSoundSink retrying=new JavaSoundSink(format->{
            if(attempts.incrementAndGet()==1)throw new LineUnavailableException("transient test device failure");
            return device.line;
        });
        try {
            retrying.reset(1);check(retrying.offer(1,pcm(480,2,1111)),"first frame lazily opens transient device");
            await(()->retrying.diagnostics().recovering(),"device failure enters bounded retry state");
            check(retrying.offerResult(1,pcm(480,2,2222))==PcmSink.OfferResult.DEVICE_RECOVERING,"retry state refuses stale PCM explicitly");
            retrying.reset(2);
            await(()->attempts.get()==2 && !retrying.diagnostics().recovering(),"audio daemon reopens after backoff");
            check(retrying.offerResult(1,pcm(480,2,3333))==PcmSink.OfferResult.STALE,"old generation still rejected after reopen");
            check(retrying.offer(2,pcm(480,2,4444)),"new current PCM resumes after reopen");
            await(()->device.writes()>0,"reopened device really receives samples");
            check(device.lastSample()==4444,"failed attempt PCM is not replayed after recovery");
            check(retrying.diagnostics().deviceFailures()==1 && retrying.diagnostics().reopens()==1,"device recovery counters exact");
        } finally { retrying.close(); }
        await(device::closed,"recovered device closes on daemon");

        AtomicInteger failedAttempts=new AtomicInteger();
        JavaSoundSink broken=new JavaSoundSink(format->{failedAttempts.incrementAndGet();throw new LineUnavailableException("persistent test device failure");});
        try {
            broken.offer(0,pcm(480,2,1));await(()->broken.diagnostics().failed(),"permanent device failure exhausts finite budget");
            check(failedAttempts.get()==4 && broken.diagnostics().reopens()==3,"at most initial open plus3 delayed reopens");
            check(broken.offerResult(0,pcm(480,2,1))==PcmSink.OfferResult.DEVICE_FAILED,"exhausted device reports failure not backpressure");
            Thread.sleep(300);check(failedAttempts.get()==4,"failed device cannot create unbounded retry work");
        } finally { broken.close(); }

        AtomicInteger cancelledAttempts=new AtomicInteger();
        JavaSoundSink cancelled=new JavaSoundSink(format->{cancelledAttempts.incrementAndGet();throw new LineUnavailableException("cancel test");});
        cancelled.offer(0,pcm(480,2,1));await(()->cancelled.diagnostics().recovering(),"close-during-backoff gate reached");
        long start=System.nanoTime();cancelled.close();
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"close never joins retry timer");
        Thread.sleep(350);check(cancelledAttempts.get()==1,"closing cancels pending reopen");
    }
    private static final class Device implements InvocationHandler {
        final SourceDataLine line=(SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},this);
        final List<String> calls=new CopyOnWriteArrayList<>();final List<byte[]> written=new CopyOnWriteArrayList<>();
        final CountDownLatch writeEntered=new CountDownLatch(1),releaseWrite=new CountDownLatch(1);
        volatile int flushes,openedBuffer;volatile boolean closed;
        @Override public Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
            calls.add(Thread.currentThread().getName()+":"+method.getName());
            return switch(method.getName()){
                case "open"->{openedBuffer=(int)args[1];yield null;}
                case "available"->1920;
                case "write"->{byte[] bytes=(byte[])args[0];int offset=(int)args[1],length=(int)args[2];
                    written.add(Arrays.copyOfRange(bytes,offset,offset+length));writeEntered.countDown();
                    if(!releaseWrite.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test write gate timeout");yield length;}
                case "flush"->{flushes++;yield null;}
                case "close"->{closed=true;yield null;}
                case "start","stop"->null;
                default->throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
