package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcInputFocusTest {
    @Test void ordinaryFastEdgesHaveNoAddedDelay(){var f=new SfcInputFocus();for(int n=0;n<100;n++){assertEquals(1,f.sample(1));assertEquals(0,f.sample(0));}}
    @Test void keyHeldThroughMenuNeedsAllKeysReleased(){var f=new SfcInputFocus();assertEquals(1,f.sample(1));f.suspend();assertEquals(0,f.sample(1));assertEquals(0,f.sample(2));assertEquals(0,f.sample(3));assertEquals(0,f.sample(0));assertEquals(2,f.sample(2));}
    @Test void focusSuspensionIsIdempotentAndDoesNotReplay(){var f=new SfcInputFocus();f.suspend();f.suspend();for(int n=1;n<4096;n++)assertEquals(0,f.sample(n));assertEquals(0,f.sample(0));assertEquals(4095,f.sample(4095));}
    @Test void freshSessionResetsOnlyItsOwnFocusHistory(){var a=new SfcInputFocus();var b=new SfcInputFocus();a.suspend();assertEquals(0,a.sample(1));assertEquals(1,b.sample(1));a.reset();assertEquals(1,a.sample(1));}
}
