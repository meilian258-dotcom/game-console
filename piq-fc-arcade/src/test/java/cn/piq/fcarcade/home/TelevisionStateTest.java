package cn.piq.fcarcade.home;

import cn.piq.fcarcade.client.NoSignalPattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelevisionStateTest {
    @Test void offIsBlackAndSilentDespiteSignal() {
        var tv=new TelevisionState(false,60);tv.signal(true);
        assertFalse(tv.colorBars());assertFalse(tv.signal());assertEquals(0,tv.gain());
    }
    @Test void powerOnWithoutSignalShowsBars() {
        var tv=new TelevisionState(false,60);tv.power(true);
        assertTrue(tv.colorBars());assertFalse(tv.signal());assertEquals(.6f,tv.gain());
    }
    @Test void gameSignalAndPowerCycleDoNotLeaveStaleFrameState() {
        var tv=new TelevisionState(true,60);tv.signal(true);assertTrue(tv.signal());assertFalse(tv.colorBars());
        tv.power(false);tv.power(true);assertFalse(tv.signal());assertTrue(tv.colorBars());
    }
    @Test void volumeClampedAndRememberedAcrossPowerCycles() {
        var tv=new TelevisionState(true,-90);assertEquals(0,tv.volume());tv.volume(900);assertEquals(100,tv.volume());
        tv.volume(30);tv.power(false);assertEquals(0,tv.gain());tv.power(true);assertEquals(.3f,tv.gain());
    }
    @Test void signalLossReturnsToBars() {var tv=new TelevisionState(true,60);tv.signal(true);tv.signal(false);assertTrue(tv.colorBars());}
    @Test void barsAreOpaqueSharpAndOrdered() {
        assertEquals(0xffbfbfbf,NoSignalPattern.pixel(0,0));assertEquals(0xff00bfbf,NoSignalPattern.pixel(32,0));
        assertEquals(0xffbf0000,NoSignalPattern.pixel(192,0)); // blue, not red in ABGR
        for(int x=0;x<NoSignalPattern.WIDTH;x++)for(int y=0;y<NoSignalPattern.HEIGHT;y++)assertEquals(255,NoSignalPattern.pixel(x,y)>>>24);
        assertEquals(NoSignalPattern.pixel(0,0),NoSignalPattern.pixel(31,119));
        assertNotEquals(NoSignalPattern.pixel(31,0),NoSignalPattern.pixel(32,0));
    }
    @Test void invalidPixelCoordinatesReject() {assertThrows(IllegalArgumentException.class,()->NoSignalPattern.pixel(-1,0));assertThrows(IllegalArgumentException.class,()->NoSignalPattern.pixel(0,168));}
}
