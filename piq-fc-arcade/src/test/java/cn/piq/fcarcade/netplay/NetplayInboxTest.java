package cn.piq.fcarcade.netplay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetplayInboxTest {
 @Test void fragmentedCatchupDoesNotExhaustTinyPacketCount()throws Exception{var q=new NetplayInbox();for(int i=0;i<2048;i++)assertTrue(q.offer(new byte[]{(byte)i}));assertEquals(2048,q.bytes());for(int i=0;i<2048;i++)assertEquals((byte)i,q.poll(1)[0]);assertEquals(0,q.bytes());}
 @Test void memoryIsBoundedAndRecoveredOnRead()throws Exception{var q=new NetplayInbox();for(int i=0;i<128;i++)assertTrue(q.offer(new byte[16384]));assertFalse(q.offer(new byte[1]));assertEquals(2*1024*1024,q.bytes());q.poll(1);assertTrue(q.offer(new byte[16384]));}
 @Test void fragmentCountAlsoBounded(){var q=new NetplayInbox();for(int i=0;i<4096;i++)assertTrue(q.offer(new byte[]{1}));assertFalse(q.offer(new byte[]{1}));}
 @Test void closeDropsDataAndWakesReader()throws Exception{var q=new NetplayInbox();q.offer(new byte[]{1});q.close();assertNull(q.poll(1000));assertFalse(q.offer(new byte[]{1}));assertEquals(0,q.bytes());}
 @Test void malformedFragmentsRejected(){var q=new NetplayInbox();assertFalse(q.offer(new byte[0]));assertFalse(q.offer(new byte[16385]));}
}
