package cn.piq.fcarcade.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoSignalToneTest {
    @Test void stateVolumeAndFiniteDistanceGate() {
        assertTrue(NoSignalTone.audible(true,false,60,0));
        assertTrue(NoSignalTone.audible(true,false,1,143.99));
        assertFalse(NoSignalTone.audible(true,false,60,144));
        assertFalse(NoSignalTone.audible(false,false,60,1));
        assertFalse(NoSignalTone.audible(true,true,60,1));
        assertFalse(NoSignalTone.audible(true,false,0,1));
        assertFalse(NoSignalTone.audible(true,false,60,Double.NaN));
        assertFalse(NoSignalTone.audible(true,false,60,Double.POSITIVE_INFINITY));
        assertFalse(NoSignalTone.audible(true,false,60,-1));
    }
    @Test void quietZeroCentredOneKilohertz() {
        long sum=0;int peaks=0;short previous=NoSignalTone.sample(44099);
        for(long i=44100;i<88200;i++) {
            short current=NoSignalTone.sample(i);sum+=current;
            assertTrue(Math.abs(current)<=2622);
            if(previous<=0 && current>0)peaks++;
            previous=current;
        }
        assertEquals(0,sum); assertEquals(1000,peaks);
    }
    @Test void repeatablePhaseAndSmoothStartup() {
        assertEquals(0,NoSignalTone.sample(0));
        assertTrue(Math.abs(NoSignalTone.sample(1))<20);
        for(int i=220;i<2000;i++) assertEquals(NoSignalTone.sample(i),NoSignalTone.sample(44100L*100000+i));
        assertThrows(IllegalArgumentException.class,()->NoSignalTone.sample(-1));
    }
}
