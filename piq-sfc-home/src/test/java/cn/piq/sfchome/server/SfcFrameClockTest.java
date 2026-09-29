package cn.piq.sfchome.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcFrameClockTest {
    @Test void palAlternatesTwoThree(){var c=new SfcFrameClock(50);for(int i=0;i<20;i++)assertEquals(i%2==0?2:3,c.tick());}
    @Test void ntscSixtyIsThree(){var c=new SfcFrameClock(60);for(int i=0;i<100;i++)assertEquals(3,c.tick());}
    @Test void actualNtscFractionDoesNotDrift(){var c=new SfcFrameClock(60.098811);long total=0;for(int i=0;i<20000;i++)total+=c.tick();assertEquals(60098,total);}
    @Test void boundedOneToFourFrames(){for(double fps:new double[]{49,50.01,51,59,60.099,61}){var c=new SfcFrameClock(fps);for(int i=0;i<200;i++){int n=c.tick();assertTrue(n>=2&&n<=4);}}}
    @Test void unsupportedOrNonFiniteRejected(){for(double fps:new double[]{0,48,52,55,58,62,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->new SfcFrameClock(fps));}
}
