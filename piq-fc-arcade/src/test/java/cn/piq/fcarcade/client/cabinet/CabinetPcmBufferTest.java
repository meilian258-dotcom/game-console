package cn.piq.fcarcade.client.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetPcmBufferTest {
    @Test void aggregatesSmallPollsAndPreservesSampleClock(){var q=new CabinetPcmBuffer();for(int i=0;i<8;i++)q.offer(new short[800]);var a=q.poll(4800);assertEquals(0,a.firstSample());assertEquals(4800,a.samples().length);var b=q.poll(4800);assertEquals(2400,b.firstSample());assertEquals(1600,b.samples().length);assertNull(q.poll(4800));}
    @Test void slowReceiverDropsOldAudioButClockShowsGap(){var q=new CabinetPcmBuffer();q.offer(new short[2000]);assertEquals(0,q.poll(1000).firstSample());for(int i=0;i<40;i++)q.offer(new short[2000]);assertTrue(q.bufferedShorts()<=28800);assertTrue(q.poll(4800).firstSample()>500);}
    @Test void oversizedExistingFrameRetainsNewest300ms(){var q=new CabinetPcmBuffer();q.offer(new short[32768]);assertEquals(28800,q.bufferedShorts());assertEquals((32768-28800)/2,q.poll(4800).firstSample());}
    @Test void clearDoesNotRewindClock(){var q=new CabinetPcmBuffer();q.offer(new short[4000]);q.clear();q.offer(new short[2000]);assertEquals(2000,q.poll(4800).firstSample());}
    @Test void rejectsNonStereoAndUnboundedBlocks(){var q=new CabinetPcmBuffer();assertThrows(IllegalArgumentException.class,()->q.offer(new short[3]));assertThrows(IllegalArgumentException.class,()->q.offer(new short[32770]));assertThrows(IllegalArgumentException.class,()->q.poll(9602));}
}
