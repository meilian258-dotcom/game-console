package cn.piq.mdhome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MdPublicInputBufferTest {
    @Test void independentPortsAndShortPresses(){
        var b=new MdPublicInputBuffer(2);b.offer(0,0);b.offer(1,0);
        b.offer(0,1);b.offer(0,0);b.offer(1,256);
        assertArrayEquals(new int[]{1,256},b.next());assertArrayEquals(new int[]{0,256},b.next());
        b.offer(0,2);assertArrayEquals(new int[]{2,256},b.next());
    }
    @Test void releaseOnePortDoesNotReleaseOtherPlayer(){
        var b=new MdPublicInputBuffer(2);b.offer(0,0);b.offer(1,0);b.offer(0,1);b.offer(1,256);b.next();b.release(0);
        assertArrayEquals(new int[]{0,256},b.next());b.offer(0,1);assertArrayEquals(new int[]{0,256},b.next());
        b.offer(0,0);b.offer(0,1);assertArrayEquals(new int[]{1,256},b.next());
    }
    @Test void neutralAndClearDiscardOldInput(){
        var b=new MdPublicInputBuffer(2);b.offer(0,1);b.offer(1,2);assertArrayEquals(new int[]{0,0},b.next());
        b.offer(0,0);b.offer(0,1);b.offer(0,0);b.clear();assertArrayEquals(new int[]{0,0},b.next());
    }
    @Test void boundedPerPortAndDuplicateTransitions(){
        var b=new MdPublicInputBuffer(2);b.offer(0,0);b.offer(1,0);for(int i=0;i<500;i++)b.offer(0,1);
        assertArrayEquals(new int[]{1,0},b.next());for(int i=0;i<128;i++)b.offer(0,i%2);
        assertThrows(IllegalStateException.class,()->b.offer(0,0));b.offer(1,256);assertEquals(256,b.next()[1]);
    }
    @Test void invalidPortAndBits(){
        var b=new MdPublicInputBuffer(1);b.offer(0,0);b.offer(0,4095);assertArrayEquals(new int[]{4095,0},b.next());
        assertThrows(IllegalArgumentException.class,()->b.offer(1,1));assertThrows(IllegalArgumentException.class,()->b.release(-1));
        assertThrows(IllegalArgumentException.class,()->b.offer(0,4096));assertThrows(IllegalArgumentException.class,()->new MdPublicInputBuffer(3));
    }
}
