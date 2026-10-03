package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.ContentCardDownloadBuffer;
import cn.piq.fcarcade.home.content.ContentCardStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardDownloadBufferTest {
    private final byte[] bytes={1,2,3,4,5};
    @Test void completeVerifiedDownloadReturnsOnlyDetachedContent()throws Exception{
        var hash=ContentCardStore.hash(bytes);var buffer=new ContentCardDownloadBuffer(hash,bytes.length,8);
        buffer.accept(hash,5,0,new byte[]{1,2});assertEquals(2,buffer.offset());assertFalse(buffer.complete());
        assertThrows(IllegalStateException.class,buffer::take);
        buffer.accept(hash,5,2,new byte[]{3,4,5});assertTrue(buffer.complete());
        var result=buffer.take();assertArrayEquals(bytes,result);assertFalse(buffer.complete());
        var calls=new AtomicInteger();ContentCardDownloadBuffer.validate(hash,result,b->calls.incrementAndGet());assertEquals(1,calls.get());
        assertThrows(IllegalStateException.class,buffer::take);
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,5,5,new byte[]{1}));
    }
    @Test void offerAllocationIsBoundedBeforeAnyContentArrives(){
        for(int size:new int[]{-1,0,9,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new ContentCardDownloadBuffer("a".repeat(64),size,8));
        assertThrows(IllegalArgumentException.class,()->new ContentCardDownloadBuffer("wrong",8,8));
        assertThrows(IllegalArgumentException.class,()->new ContentCardDownloadBuffer("a".repeat(64),8,ContentCardStore.MAX_BYTES+1));
    }
    @Test void wrongIdentityOutOfOrderDuplicatesEmptyAndOversizedChunksAreRejected(){
        var hash=ContentCardStore.hash(bytes);var buffer=new ContentCardDownloadBuffer(hash,5,8);
        assertThrows(IllegalArgumentException.class,()->buffer.accept("b".repeat(64),5,0,new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,6,0,new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,5,1,new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,5,0,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,5,0,new byte[6]));
        buffer.accept(hash,5,0,new byte[]{1});assertEquals(1,buffer.offset());
        assertThrows(IllegalArgumentException.class,()->buffer.accept(hash,5,0,new byte[]{1}));
        var large=new ContentCardDownloadBuffer(hash,ContentCardStore.CHUNK+1,ContentCardStore.MAX_BYTES);
        assertThrows(IllegalArgumentException.class,()->large.accept(hash,ContentCardStore.CHUNK+1,0,new byte[ContentCardStore.CHUNK+1]));
    }
    @Test void hashAndMachineValidatorBothMustPassBeforeCallerGetsSuccess(){
        var hash=ContentCardStore.hash(bytes);var calls=new AtomicInteger();
        assertThrows(IOException.class,()->ContentCardDownloadBuffer.validate("b".repeat(64),bytes,b->calls.incrementAndGet()));assertEquals(0,calls.get());
        assertThrows(IOException.class,()->ContentCardDownloadBuffer.validate(hash,bytes,b->{throw new IOException("Not a supported ROM");}));
        var mutated=Arrays.copyOf(bytes,bytes.length);
        assertThrows(IOException.class,()->ContentCardDownloadBuffer.validate(hash,mutated,b->b[0]=0));
    }
}
