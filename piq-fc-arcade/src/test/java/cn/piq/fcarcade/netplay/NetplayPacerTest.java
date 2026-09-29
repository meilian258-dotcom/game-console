package cn.piq.fcarcade.netplay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetplayPacerTest {
 @Test void largeStateIsPacedNotDropped(){var p=new NetplayPacer();long now=1_000_000_000L;for(int i=0;i<128;i++){long wait=p.delay(16384,now);assertTrue(wait>=0&&wait<22_000_000);now+=wait;}assertTrue(now>3_000_000_000L);}
 @Test void idleInputHasNoExtraDelay(){var p=new NetplayPacer();assertEquals(0,p.delay(40,1_000_000_000));assertEquals(0,p.delay(40,2_000_000_000));}
 @Test void invalidCountFails(){var p=new NetplayPacer();assertThrows(IllegalArgumentException.class,()->p.delay(0,1));assertThrows(IllegalArgumentException.class,()->p.delay(16385,1));}
}
