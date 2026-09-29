package cn.piq.pvz.runtime;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameMailboxTest {
    @Test void empty(){assertNull(new FrameMailbox(16).poll());}
    @Test void latestOnly(){var m=new FrameMailbox(16);for(int i=0;i<1000;i++){var f=m.acquire();assertNotNull(f);f[0]=(byte)i;m.publish(f);}var f=m.poll();assertEquals((byte)999,f[0]);m.release(f);assertNull(m.poll());}
    @Test void ownsExactlyThreeBuffers(){var m=new FrameMailbox(16);var buffers=Collections.newSetFromMap(new IdentityHashMap<byte[],Boolean>());for(int i=0;i<300;i++){byte[] f=m.acquire();buffers.add(f);m.publish(f);m.release(m.poll());}assertEquals(3,buffers.size());}
    @Test void retainedFrameNeverOverwritten(){var m=new FrameMailbox(16);byte[] first=m.acquire();Arrays.fill(first,(byte)42);m.publish(first);byte[] held=m.poll();for(int i=0;i<100;i++){var f=m.acquire();Arrays.fill(f,(byte)i);m.publish(f);}for(byte b:held)assertEquals(42,b);m.release(held);}
    @Test void exhaustionIsBounded(){var m=new FrameMailbox(16);var a=m.acquire();var b=m.acquire();var c=m.acquire();assertNull(m.acquire());m.release(a);m.release(b);m.release(c);}
    @Test void stealPendingInsteadOfAllocating(){var m=new FrameMailbox(16);var a=m.acquire();var b=m.acquire();var c=m.acquire();m.publish(c);assertSame(c,m.acquire());assertNull(m.poll());m.release(a);m.release(b);m.release(c);}
    @Test void overReleaseRejected(){var m=new FrameMailbox(16);assertThrows(IllegalStateException.class,()->m.release(new byte[16]));}
    @Test void twoThreadOwnership() throws Exception {
        var m=new FrameMailbox(256);var done=new AtomicBoolean();var error=new AtomicReference<Throwable>();
        Thread producer=new Thread(()->{try{for(int i=0;i<100000;i++){byte[] f=m.acquire();if(f!=null){Arrays.fill(f,(byte)i);m.publish(f);}}}catch(Throwable e){error.set(e);}finally{done.set(true);}});
        producer.start();int consumed=0;
        do{byte[] f=m.poll();if(f!=null){byte value=f[0];for(byte b:f)assertEquals(value,b);m.release(f);consumed++;}}while(!done.get());
        producer.join(5000);assertFalse(producer.isAlive());assertNull(error.get());assertTrue(consumed>0);
    }
}
