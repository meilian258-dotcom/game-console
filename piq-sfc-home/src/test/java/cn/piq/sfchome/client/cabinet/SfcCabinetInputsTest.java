package cn.piq.sfchome.client.cabinet;
import cn.piq.sfcarcade.core.SfcButton;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetInputsTest {
    @Test void allTwelveBitsMatchLibretro(){String[] order={"B","Y","SELECT","START","UP","DOWN","LEFT","RIGHT","A","X","L","R"};for(int i=0;i<12;i++)assertEquals(1<<i,SfcButton.valueOf(order[i]).mask());}
    @Test void fastPressReleaseAndHeldStateArePreserved(){var q=new SfcCabinetInputs();assertTrue(q.offer(1,256));assertTrue(q.offer(0,0));assertEquals(new SfcCabinetInputs.Pair(1,256),q.nextFrame());assertEquals(new SfcCabinetInputs.Pair(0,0),q.nextFrame());assertEquals(new SfcCabinetInputs.Pair(0,0),q.nextFrame());}
    @Test void repeatsDoNotConsumeQueue(){var q=new SfcCabinetInputs();for(int i=0;i<1000;i++)assertTrue(q.offer(1,2));assertEquals(new SfcCabinetInputs.Pair(1,2),q.nextFrame());assertTrue(q.offer(0,0));assertEquals(new SfcCabinetInputs.Pair(0,0),q.nextFrame());}
    @Test void focusClearDiscardsQueuedPressesAndHeldState(){var q=new SfcCabinetInputs();q.offer(1,1);q.nextFrame();q.offer(2,2);q.offer(4,4);q.clear();assertEquals(new SfcCabinetInputs.Pair(0,0),q.nextFrame());assertTrue(q.offer(2,2));assertEquals(new SfcCabinetInputs.Pair(2,2),q.nextFrame());}
    @Test void fullQueueFailsWithoutDroppingEarlierTransitions(){var q=new SfcCabinetInputs();for(int i=0;i<128;i++)assertTrue(q.offer((i&1)==0?1:0,0));assertFalse(q.offer(2,0));for(int i=0;i<128;i++)assertEquals((i&1)==0?1:0,q.nextFrame().p1());assertTrue(q.offer(2,0));}
    @Test void invalidBitsRejected(){var q=new SfcCabinetInputs();assertThrows(IllegalArgumentException.class,()->q.offer(4096,0));assertThrows(IllegalArgumentException.class,()->q.offer(0,-1));}
}
